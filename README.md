# Order Integration Platform

Spring Boot and Apache Camel order processing: REST, CSV and XML intake, a Kafka pipeline with a
saga for stock, payment and shipping, GST invoices, returns with pickups and credit notes. The web
app (shop, admin console, delivery screens) is in `../order-platform-web` and is served from this
backend in production.

Payments, carriers and SMS are still simulated. Email is real SMTP, caught by Mailpit in
development.

## Run it

Requires Java 21+, Maven and Docker.

```bash
docker compose up -d      # Postgres, Kafka, Mailpit, Kafka UI, Prometheus, Grafana, LRA coordinator
mvn spring-boot:run       # API on http://localhost:9091
```

The web app, either way:

- **Development:** `cd ../order-platform-web && npm run dev`, then http://localhost:5173 (reloads on save,
  `/api` is passed to :9091).
- **As shipped:** `cd ../order-platform-web && npm run build`, then build or run this backend. The `web` Maven
  profile switches on by itself when `../order-platform-web/dist` exists and copies the app into the jar,
  so http://localhost:9091 serves it. `WebAppConfig` answers every screen (`/shop`, `/admin/orders/…`) with
  `index.html` and caches the hashed files for a year.

Keycloak must allow the address users sign in from: the `order-platform-web` client needs
`http://localhost:9091/*` (and `:5173/*` for development) under *Valid redirect URIs* and
*Valid post logout redirect URIs*, and the origin under *Web origins*. The realm file has them; a
Keycloak that imported an older file needs them added in the admin console.

Stop the backend with **Ctrl+C**, not Ctrl+Z: Ctrl+Z only pauses it, and the paused copy keeps its
database connections and wakes up with old code if resumed (`jobs` lists paused ones, `kill %1` ends one).

| Tool | URL | What it is for |
|---|---|---|
| Application | http://localhost:9091 | Web app (after `npm run build`) and the API |
| Web app (dev) | http://localhost:5173 | `npm run dev` in ../order-platform-web |
| Mailpit | http://localhost:8025 | Inbox for every email the app sends |
| Kafka UI | http://localhost:8085 | Topics, consumers and `order.created.DLT` |
| Prometheus | http://localhost:9090 | Metrics, under Status > Targets |
| Grafana | http://localhost:3000 | "Order Platform" dashboard (admin / admin) |
| LRA coordinator | http://localhost:8087/lra-coordinator | Open sagas, as JSON |

## Test it

```bash
mvn verify
```

Needs Docker running. Tests start their own throwaway Postgres, Kafka and Mailpit, so they never
touch the containers above or your data. A full run takes about 3 minutes.

## Order life cycle

```
PENDING -> INVENTORY_RESERVED -> CONFIRMED -> SHIPPED -> READY_FOR_DISPATCH
        -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED
```

- `OrderStatus` defines every legal move, and all changes go through `OrderStateService`.
- An order can be cancelled up to `READY_FOR_DISPATCH`. After that the goods have left the warehouse.
- **Partial cancel** (`POST /api/orders/{id}/cancel-items` with `{items: [{sku, quantity}], reason}`; the
  customer who ordered, or an admin): while the order is `CONFIRMED`, `SHIPPED` or `READY_FOR_DISPATCH`,
  some units can be cancelled and the rest still ships. The invoice is a GST document and is not changed.
  Instead, a `CANCELLATION` credit note credits each unit at its share of the line, coupon discount
  included. The order gets its invoice number first if it has none yet. The units go back on the shelf
  (`RELEASE` in the stock ledger). A prepaid order is refunded that amount. A cash-on-delivery order
  not paid yet owes less at the door (`amountDue` = `total` − `cancelledTotal`), and its credit note has no
  payment. Cancelling every unit that is left cancels the whole order instead. Lines keep the ordered
  `quantity`, with `cancelledQuantity` and `activeQuantity`. The driver's parcel list, shipping, restocking
  and returns all use the active quantity. Revenue leaves out cancelled units. A dead letter replay of
  such an order is refused, because running it again would charge the full total.
- **Acting on an order someone else changed** (`orders.version`, V24): every change to an order raises
  its `version`. `GET /api/orders/{id}` returns it in the body and as `ETag: "7"`. Actions a person takes
  send it back as `If-Match: "7"`: cancel, cancel items, goodwill refund, assign driver and return to stock.
  If the order changed after they opened it (the customer cancelled, another admin assigned a driver,
  the pipeline moved it on), nothing is done. The reply is **412** with
  `{status: "CHANGED", reason, orderStatus, version}` and the current `ETag`, so they reload and decide
  again. The check runs under the order's row lock, and these actions always raise the version, even
  when they only touch other tables, such as a driver assignment or a credit note. Without `If-Match` an
  action works on the order as it is now, so partners and scripts are unaffected. Two saves of one order
  at the same instant are refused by Hibernate's version check, which answers **409**. The pipeline
  always reads an order under its row lock before saving it, so the check never fails there.
- Failures end in `OUT_OF_STOCK`, `FRAUD_REJECTED`, `PAYMENT_FAILED` or `FULFILLMENT_FAILED`.
- `SHIPPED` means a carrier is booked. Stock leaves the reservation at `IN_TRANSIT`.
- Every order is screened against the blacklist. Orders over $1,000 also get the full fraud evaluation.

## User profiles

Every signed-in user has a profile (`ProfileService`, Flyway V21 `user_profiles`):
- `GET` and `PUT /api/me/profile`: name and mobile for everyone. Mobiles are stored as `+91-98450-00101`, and a delivery user must have one.
- The other fields depend on the role:
  - Customers: date of birth (18 or older) and gender.
  - Delivery users: vehicle type, plus the vehicle number (`KA01AB1234` or BH series) and driving licence (15 characters) for anything motorised, an emergency contact (not their own number) and a UPI ID for payouts.
  - Admins: job title and department.
- Parts for roles the user does not have are ignored. Email and password stay in Keycloak.
- `POST /api/me/photo` (multipart `file`) keeps the middle square at 256 px and re-encodes it, which drops EXIF and GPS data. It is stored in the photo bucket under `profiles/{userId}/`. A new photo or `DELETE /api/me/photo` removes the old file once the change is committed.
- `GET /api/users/{userId}/photo` is private: only the user and admins get it, and anyone else gets 404. `/api/me` returns `photoUpdatedAt`, so the page can tell when the photo changed.
- **Delivery partner** (`DeliveryPartnerService`): `GET /api/orders/{id}/delivery-partner`, and `/photo` for their photo, is for the order's customer and admins. It shows the driver from pickup until 14 days after delivery (the rating window): first name and initial, photo, vehicle type and number, average rating and number of deliveries. It never shows their phone, licence or full name. Before pickup `partner` is null, because the driver can still give the job back.
- **Inactive accounts** (`AccountStatusService`, Flyway V22):
  - `POST /api/admin/users/{id}/deactivate` takes `{reason}`; `/activate` turns the user back on. Only customers and drivers can be changed, and never the admin's own account; admin and service accounts are managed in Keycloak.
  - An inactive user still signs in, but `InactiveAccountFilter` answers every other API call with 403 `ACCOUNT_INACTIVE` "Your account is inactive. Please contact the administrator." Only `/api/me` (which returns `active: false`) and `/api/public/**` still work. Each pod caches the active flag for 30 seconds; a change is seen at once on the pod that made it.
  - Turning a driver off gives back the jobs they have not started (deliveries and return pickups go back on the list). It is refused while they carry a parcel or are on the way to a pickup.
  - The user is emailed when their account is paused and when it is active again. Who turned them off, when and why shows on the admin page and is cleared on reactivation.
- **Admin users** (`AdminUserService`, admin only):
  - `GET /api/admin/users?q=&role=&active=&page=&size=` searches name, email, user id and phone digits. `%` and `_` are matched literally.
  - `GET /api/admin/users/{id}` returns the profile plus activity: orders, spending after refunds and returns for a customer; deliveries, pickups, failed attempts, earnings and rating for a driver.

## Placing an order

- The server always issues `orderId` (`ORD-<uuid>`). An `orderId` sent by the caller, or the order
  number in a CSV or XML file, is stored as `orderReference`. Each customer can use a reference once,
  so a retried request gets a 409 instead of a second order.
- Only `orderId`, `customerId`, `items`, `shippingAddress`, `couponCode`, `paymentMethod`
  (`PREPAID` or `COD`) and `deliverySlot` (`{"date": "2026-11-14", "window": "MORNING"}`) are read
  from the request. Status, payment, invoice, carrier and fraud fields are ignored on `/api/orders`
  and refused with a 400 on `/api/orders/validated`. A coupon, COD or slot that does not apply refuses
  the order with a 400 that says why.

## Promotions (shop banners)

Admins manage banners under `/api/admin/promotions` (`GET`, `POST`, `PUT /{id}`, `PATCH /{id}` with
`active`, `DELETE /{id}`, `POST/DELETE /{id}/image`). Each has a headline, message, button text, a
colour, an optional wide picture (the middle 3:1 strip is kept, 1500 × 500, metadata dropped, served
at `/api/public/images/promotions/…`), dates, a priority (0–100) and what it opens: a `PRODUCT`, a
`CATEGORY`, or a `COUPON` (the shop then lists the products that coupon is for).

`GET /api/public/promotions` returns the live ones, highest priority first, at most 8. A banner is
shown only while what it advertises can be bought: a product off sale, a hidden category, or a coupon
that is off, outside its dates or used up takes it down by itself (`status: UNAVAILABLE` with
`unavailableReason` for admins). Customers' views and clicks are counted
(`POST /api/promotions/{id}/view|click`) for the click rate; staff browsing is not.

## Checkout: coupons, cash on delivery, delivery slots

- **Quote** (`POST /api/checkout/quote`, customers): the same body as an order; answers what it would
  cost now: each line with its discount, the coupon applied or `couponError` (why not), GST, and
  whether cash on delivery is available. Nothing is saved.
- **Coupons** (`coupons` table; admins: `GET/POST /api/admin/coupons`, `PATCH /api/admin/coupons/{code}`
  with `active`, `endsAt` or `clearEndsAt: true`; the code, discount and limits cannot change once
  customers have been told them; `updatedAt`/`updatedBy` record the last change): percent (at most 90%, with an optional cap) or a flat amount; a minimum
  order (before the discount), start and end, total uses, uses per customer, first order only.
  `GET /api/checkout/offers` lists those the signed-in customer can use. The discount comes off the
  price including GST and is shared across the lines by value (`order_items.discount`); GST is then
  worked out on what is left, so the invoice shows it as a discount (CGST Act s.15(3)(a)). A clothing
  line's GST rate follows its price per piece after the discount. Returns refund what was paid for the
  line. Uses are counted from live orders: a cancelled or failed order gives its use back. The coupon's
  row is locked while an order is placed, so two orders cannot both take the last use.
  Development coupons: `WELCOME10`, `SAVE200`, `FESTIVE15` (`db/demo/R__demo_coupons.sql`).
- **Coupons for chosen products** (`coupon_targets`, V23): send `skus` and/or `categoryIds` when
  creating a coupon (or in the `PATCH`, which replaces them) and it takes money off only those lines;
  a main category includes its sub-categories. Its minimum counts only those lines, and an order with
  none of them gets "…is for Lamps only, and none of them is in your cart". `appliesTo` on the coupon
  (and on `/api/checkout/offers`) names them. Orders already placed keep the discount they got.
- **Offers in the shop** (GraphQL): `Product.offers` lists the live coupons that take money off a
  product (ones for chosen products first; per-customer limits are still checked at checkout), and
  `ProductFilter.offer: "LAMPS20"` lists the products a coupon is for.
- **Cash on delivery** (`paymentMethod: "COD"`): offered up to `ORDERS_COD_MAX_ORDER_VALUE` (₹50,000),
  and not to customers with 2 COD parcels returned undelivered in 180 days. The pipeline confirms the
  order without charging; the driver must confirm the cash (`"cashCollected": true`) before the code
  is checked, and the cash is recorded as a `CHARGE` with gateway `CASH_ON_DELIVERY`. Cancelling or
  returning one before that refunds nothing. A refund after delivery goes by bank transfer or UPI.
  With `auto-release` off, the warehouse's `WAREHOUSE_PACKED` signal alone releases a COD order.
- **Delivery slots** (`GET /api/checkout/slots?pin=560038`): the 6 delivery days from the standard
  delivery's latest date, skipping Sundays and holidays, each with 9 AM–1 PM, 1 PM–5 PM and 5 PM–9 PM.
  Each window takes `ORDERS_SLOT_CAPACITY` (40) orders per day in each regional hub (north, south, east,
  west); an advisory lock keeps two orders from taking the last place. Cancelled orders free theirs.
  Drivers see the booked slot and any cash to collect.

## Customer messages

Email and SMS go out for `CONFIRMED` (payment received, or for cash on delivery the amount to have
ready; with the booked delivery slot), `IN_TRANSIT` (shipped),
`OUT_FOR_DELIVERY` (with the delivery code), `DELIVERED`, `CANCELLED`, and each failure status.

- Each message is saved in `customer_notifications` in the same transaction as the order change,
  so it is never lost after a crash and never sent for a change that rolled back.
- A poller sends due messages every second. If the mail server is down it retries after 30 s,
  doubling the wait each time, and marks the message `FAILED` after 6 attempts. The order is never affected.
- A pod that stops between sending and recording a message sends it again after 5 minutes, so a
  customer can, rarely, get the same email twice.
- The delivery code's text is removed from the table once sent.

## Payments

`payment_transactions` keeps every charge and refund. The payment columns on `orders` describe
only the latest attempt; a DLQ replay starts a new attempt without losing the earlier rows.

## Processing orders side by side

`order.created` has 6 partitions (`ORDERS_KAFKA_ORDER_PARTITIONS`), created or raised at startup,
and each pod runs 3 consumers (`ORDERS_KAFKA_ORDER_CONSUMERS`). An order's id picks its partition,
so a slow or failing order holds up only the orders on its own partition. The partition count caps
the consumers across all pods: raise it before running more than 2 pods. Production Kafka needs
`ORDERS_KAFKA_REPLICATION_FACTOR=3`.

## Live updates across pods

Pages get changes as they happen through `GET /api/stream/orders` (Server-Sent Events). A browser
keeps that stream open to one pod, but the change it is waiting for can happen on any pod, so no pod
writes to browsers directly:

1. After the database commit, the pod that made a change publishes a small event to the Kafka topic
   `platform.events` (key = order id, kept for 1 hour). It carries the admin's view of the order and the
   customer's view (no fraud score or carrier cost).
2. Every pod reads every event: each uses a consumer group of its own (`live-updates-<pod>`), starting
   at the newest event and storing no offsets (`LiveEventsRoute`).
3. Each pod passes the event to the browsers connected to it: admins get everything, a customer only
   their own orders, and dead-letter and route events go to admins only (`OrderEventBroadcaster`).

If Kafka cannot take an event, the pod still tells its own browsers. A page that was disconnected
reloads its data when the stream reconnects, so old events are never replayed.

The rule behind it, used across the platform: **nothing is sent to Kafka before the database commits.**
New orders and dead-letter replays go through the outbox table; live events wait for the commit.

## Product photos and categories

Photos are stored in **MinIO** (any S3-compatible store works), in the bucket `order-platform-products`.
Locally this is the MinIO of the hub project (`payingguest-minio`, ports 9000/9001), shared safely:

- The app has its **own MinIO user**, `order-platform-app`, whose policy allows only this bucket: it cannot
  read the hub's `payingguest-images` or create buckets. Its key is in `config/application.yml`
  (read automatically when the app runs from this folder; owner-only, never share it). Elsewhere set
  `ORDERS_STORAGE_ENDPOINT`, `ORDERS_STORAGE_BUCKET`, `ORDERS_STORAGE_ACCESS_KEY`, `ORDERS_STORAGE_SECRET_KEY`.
- The bucket stays **private**. Browsers get photos from the backend at `/api/public/images/<key>`, so
  they only ever talk to one address; keys never change, so photos are cached for a year.
- Uploads (admin, `POST /api/admin/catalog/{sku}/images`) are decoded, never trusted: JPEG, PNG or WebP
  only (no SVG), at most 5 MB, 200 px to 10,000 px a side and 30 megapixels. Phone photos are turned
  upright, re-encoded as a 1200 px picture and a 400 px thumbnail, and lose their metadata (GPS
  position). At most 8 photos per product; the first is the main photo.
- Without a storage key the shop still works and shows placeholders; uploads answer 503.

To set up the bucket and user on another MinIO (as an administrator, with `mc`):

```bash
mc mb local/order-platform-products
mc admin policy create local order-platform-products-rw policy.json   # s3:Get/Put/DeleteObject on the bucket only
mc admin user add local order-platform-app <secret>
mc admin policy attach local order-platform-products-rw --user order-platform-app
```

**Categories** go two levels deep (*Office → Pens*). The shop filters by main category, which includes
its sub-categories (`/api/public/catalog?category=office`). A category with products cannot be
deleted, only switched off. **Descriptions** are plain text (blank lines separate paragraphs), so
nothing a seller types can turn into markup on the page.

## Shop search, delivery dates and sharing

- **Search and filters** use GraphQL at `/graphql` (try queries at `/graphiql` while developing):
  Postgres full-text search plus trigram matching, so typos and the start of a word still match
  ("hedphones", "kurt"). Filters return counts that ignore their own filter. Limits: depth 8, 48 per page.
- **Development shop:** 50 categories and 160 products with specifications and drawn photos
  (`src/main/resources/demo/catalogue.json`, made by `make_catalogue.py`; fictional brands). Off in
  production: `ORDERS_DEMO_CATALOGUE=false`.
- **Clothing and footwear GST** follows the price (categories with the `APPAREL_2500` rule): 5% up to
  Rs 2,500 a piece before GST, 18% above. Rates in the demo data are for demonstration; confirm real
  rates with an accountant.
- **Delivery dates by PIN** (`deliveryEstimate` in GraphQL): ordered by 2 PM on a dispatch day it
  leaves that day; 2–3 delivery days, 3 more for hill states, the North East and the islands. Sundays
  and **holidays** are skipped (`DeliveryEstimator`, `HolidayCalendar`): a national holiday stops
  dispatch and delivery everywhere, a state's holiday only deliveries in that state, and the estimate
  lists the holidays it skipped. Admins keep the list (`/api/admin/holidays`, table `public_holidays`);
  Republic Day, Independence Day and Gandhi Jayanti are listed for 2026–2028, festivals are added
  each year. PINs come from India Post's *All India Pincode Directory* (download the
  CSV from data.gov.in, which asks for a name and a captcha), imported under Operations → Reference
  data, or at startup from `ORDERS_PIN_DIRECTORY_FILE`. A PIN not in the directory is refused. Before
  the directory is imported, any well-formed PIN gets a wider date that allows for the farthest
  states, marked approximate. *Contains data from the Department of Posts, Government of India,
  under the Government Open Data Licence – India.*
- **Most per order:** a product may limit how many one customer order holds; staff and partner
  (CSV/XML) orders are not limited.
- **Shared links:** `/shop/<sku>` pages carry Open Graph tags (title, price, description, photo), so
  WhatsApp and others show a preview card. Only when the backend serves the built app.
- **Product photos on order and return lines:** `GET /api/public/thumbnails?sku=A&sku=B` (up to 100)
  gives each product's name and main photo, including products no longer sold.
- **Wishlist** (`/api/wishlist`, customers only): `GET` the list with today's price and stock,
  `PUT /api/wishlist/{sku}` to save (body `{"notifyWhenAvailable": true}` also asks for the
  back-in-stock email), `DELETE` to remove. Up to 200 products; a product taken off sale stays on
  the list, marked as no longer sold.
- **Back-in-stock email:** when available stock goes from 0 to more (goods received, an order
  cancelled), or a product with stock is put back on sale, everyone who asked is emailed once,
  with a link to `ORDERS_SHOP_URL/shop/<sku>` (default `http://localhost:5173`). The email is queued
  in the same transaction as the stock change, through the customer-notification outbox, so it is
  sent only if the change is committed and is retried while the mail server is down. Asking again
  on the product page re-arms it.

## Ratings and reviews

- Only a customer who received the product can review it (`PUT /api/reviews/{sku}` with `rating` 1–5,
  optional `title` and `body`), once per product; sending again edits it. `GET /api/reviews` lists the
  customer's reviews and the delivered products still to review; `GET /api/reviews/{sku}` says whether
  they may review it.
- Stars alone are published at once. A review with words waits for an admin (`/api/admin/reviews`,
  approve, or reject with a note that is emailed to the customer). An edit with words goes back to the queue.
- The product's average and count (`inventory.rating_avg`, `rating_count`) are worked out again from the
  approved reviews in the same transaction, under the product's row lock. The shop reads them, the
  1–5 star breakdown, and the published reviews (with words) through GraphQL: `Product.rating` and
  `Product.reviews(sort, page, size)`. Authors appear as first name and initial.

## Sagas

Stock, payment and shipping run as one saga. The Narayana LRA coordinator (`lra-coordinator` in
`compose.yaml`) keeps every saga, so a saga outlives the pod that started it:

- A step fails: the coordinator calls the app back at `PUT /lra-participant/compensate` to
  refund and return the stock.
- A saga is still open after `ORDERS_SAGA_TIMEOUT_MS` (5 minutes), for example because its pod
  stopped: the coordinator cancels it and calls the same compensations, which any pod can answer.
- The order records the saga holding it (`orders.saga_id`). A compensation from any other saga is
  ignored, and once recovery takes an order over, the old saga can no longer touch it.
- The coordinator sends no token, so `/lra-participant` accepts calls only from
  `ORDERS_LRA_CALLERS`, private networks by default. Narrow it to the coordinator's address in production.

| Setting | Default | What it is |
|---|---|---|
| `ORDERS_LRA_ENABLED` | `true` | `false` keeps sagas in the JVM, with recovery alone cleaning up after a crash |
| `ORDERS_LRA_COORDINATOR_URL` | `http://localhost:8087` | The coordinator |
| `ORDERS_LRA_PARTICIPANT_URL` | `http://host.docker.internal:9091` | Where the coordinator calls the app; with several pods, the service address |

## Stuck orders

Without the coordinator, a stopped app loses its sagas, and the Kafka message already counts as
processed. With it, recovery is the safety net for what the coordinator does not handle.
A recovery job looks every minute for orders that have been `PENDING`,
`INVENTORY_RESERVED` or `CONFIRMED` for over 15 minutes (`ORDERS_RECOVERY_STUCK_AFTER_MS`):

| Status | What recovery does |
|---|---|
| `PENDING` | Publishes the order again. After 3 tries: `FULFILLMENT_FAILED` and a DLQ entry to replay. With no address: `FULFILLMENT_FAILED`, nothing charged |
| `INVENTORY_RESERVED` | Returns the stock: `PAYMENT_FAILED`, as no charge was recorded |
| `CONFIRMED` | Ships it. With no address, or after 3 tries: refund and stock back, `FULFILLMENT_FAILED` |
| `SHIPPED` | After 24 hours without warehouse release (`ORDERS_RELEASE_DEADLINE_MS`): refund and stock back |

With a real payment gateway, `INVENTORY_RESERVED` must first be checked with the gateway, because
the app can stop between a charge and recording it.

## Warehouse signals

`orders.fulfillment.auto-release` (`ORDERS_AUTO_RELEASE`) decides how a booked order is released:

- `true` (default): the app releases it by itself, standing in for a warehouse during development.
- `false`: it waits for both `POST /api/fulfillment/payment-captured` and
  `POST /api/fulfillment/warehouse-packed`. Set this once a real warehouse system sends them.

## Sign-in with Keycloak

Keycloak signs users in, handles sign-up and passwords, and holds the roles. The app only checks
the tokens Keycloak issues (`KEYCLOAK_ISSUER_URI`, default `http://localhost:8084/realms/order-platform`).

- Import `ops/keycloak/order-platform-realm.json` into your Keycloak (Create realm, upload the
  file), or run `docker compose --profile keycloak up -d` where no Keycloak is running yet.
- Demo users, all with the password `demo1234`: `alice.smith@example.com` and
  `bob.johnson@example.com` (customers), `admin@example.com`, `driver1@example.com`, `driver2@example.com`.
- Roles are realm roles: `CUSTOMER` (given to everyone who signs up), `ADMIN`, `DELIVERY`, and
  `SERVICE` for systems such as the warehouse, which signs in as the `warehouse-service` client.
- Someone new in Keycloak gets a user in `app_users` on their first request, linked by their
  Keycloak id. Make someone a driver or admin by giving them the role in Keycloak.
- The `order-platform-cli` client allows password login for curl and tests. Remove it in production.

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=order-platform-cli \
  -d username=alice.smith@example.com -d password=demo1234 \
  http://localhost:8084/realms/order-platform/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9091/api/orders
```

| Role | Can do |
|---|---|
| `CUSTOMER` | Order for themselves, see, cancel and get invoices for their own orders |
| `DELIVERY` | Take, pick up and deliver orders (`/api/delivery`) |
| `ADMIN` | Everything above for any order, plus the DLQ, routes, CSV/XML intake, returns, driver assignment |
| `SERVICE` | Send warehouse and payment signals (`/api/fulfillment`) |

Another customer's order answers 404, so its existence leaks nothing. The live stream sends each
user only their own orders; admins see everything.

## Delivery

Delivery users take orders from a list that shows only the area. The name, phone and address
appear once they take it.

```
READY_FOR_DISPATCH -> taken -> pickup: IN_TRANSIT -> out for delivery: OUT_FOR_DELIVERY -> code: DELIVERED
                                                           \-> failed attempt: DELIVERY_FAILED
```

- Going out for delivery emails the customer a one-time code; the driver enters it to finish.
  Five wrong codes block it until the attempt is marked failed.
- A failed attempt goes back on the list for any driver. An admin can instead return it to stock
  and refund it (`POST /api/admin/orders/{id}/return`), or give it to a driver (`/api/admin/delivery/...`).
- Cash on delivery: the driver's list shows the amount to collect, and `deliver` needs
  `"cashCollected": true` as well as the code. Booked delivery slots show on the list too.
- `ORDERS_SIMULATE_DELIVERY=true` brings back the timer that delivers orders by itself, for demos.

### Driver pay, ratings and figures

- Each finished attempt stores what it pays: ₹40 a delivery, ₹35 a return pickup, ₹15 for a failed
  visit at the door (giving a job back before setting out pays nothing). Set with
  `ORDERS_DRIVER_PAY_DELIVERY`, `_PICKUP`, `_FAILED_VISIT`; a change applies to new attempts only.
- Customers rate the delivery of their order, 1–5 stars and an optional comment, for 14 days after
  it arrives and can change it in that time: `GET`/`PUT /api/delivery-ratings/{orderId}`.
  Drivers see the comments, never who wrote them.
- `GET /api/delivery/stats` gives the driver today, this week (from Monday), this month and this
  year: delivered, pickups collected, failed, tasks given, success rate, cash collected, earnings
  and rating, each against the same stretch of the period before. `/api/delivery/stats/history?period=DAY|WEEK|MONTH|YEAR`
  gives the chart's bars (per hour, day or month).
- Admins: `GET /api/admin/delivery/drivers/stats?period=MONTH` lists every driver;
  `/api/admin/delivery/drivers/{id}/stats` and `.../stats/history` show one.
- All times are India time: `OrderApplication` sets the JVM's zone to Asia/Kolkata, so "today"
  means the same whatever zone the server runs in.

## Prices

Orders are charged the catalogue price (`inventory.unit_price`). A price in a request or a partner
file is ignored, and an unknown product is refused with a 400.

## Users and addresses

| Table | What it holds |
|---|---|
| `app_users` | Customers, delivery users and admins. `user_id` is the `customerId` on orders |
| `user_roles` | Each user's roles: `ADMIN`, `CUSTOMER`, `DELIVERY` |
| `customer_addresses` | Saved addresses, with at most one default per customer |
| `delivery_assignments` | One row per delivery attempt, with at most one live attempt per order |

- An order needs an active customer, on every channel. Unknown or disabled customers get a 400.
- An order uses the `shippingAddress` in its request, or the customer's default address. With
  neither it is refused. The address is copied onto the order, so old orders never change.
- `shippingAddress.state` takes a state code such as `NY`, which picks the regional hub.
- Customers imported from old orders have no email, phone or address until they are added.
- A customer sets their default address with `PUT /api/me/address`; `GET /api/me` shows it.

## Database changes

Flyway owns the schema. To change it, add `src/main/resources/db/migration/V<next>__<what>.sql`.
Never edit a migration that has already run. Hibernate only validates that the entities match.

`src/main/resources/db/demo` holds the development customers and coupons. Production must set
`ORDERS_FLYWAY_LOCATIONS=classpath:db/migration` to leave them out.
