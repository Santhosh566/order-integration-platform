# Writes src/main/resources/demo/catalogue.json: the development shop (fictional brands).
# GST as revised in September 2025, for demonstration; real products need an accountant's check.
import json, re, sys, unicodedata

R = "APPAREL_2500"
def slug(s):
    plain = unicodedata.normalize('NFKD', s).encode('ascii', 'ignore').decode()
    return re.sub(r'[^a-z0-9]+', '-', plain.lower().replace("'", '')).strip('-')

# main: (name, colour, [ (sub, hsn, gst or None, rule, spec keys, [(brand, name, price, stock, specs-values, description)] ) ])
data = [
 ("Mobiles & Tablets", "#1c7ed6", [
  ("Smartphones","8517",18,None,["RAM","Storage","Display","Battery","Camera"],[
    ("Voltix","Voltix Nova 5G",18999,40,["8 GB","128 GB","6.6\" AMOLED, 120 Hz","5000 mAh","50 MP + 8 MP"],"A 5G phone with a bright 120 Hz AMOLED screen and a two-day battery."),
    ("Kairo","Kairo Edge Pro",34999,15,["12 GB","256 GB","6.7\" AMOLED, 144 Hz","4800 mAh","108 MP + 12 MP + 5 MP"],"Flagship camera, fast charging at 80 W and an aluminium frame."),
    ("Voltix","Voltix Lite 4G",8999,60,["4 GB","64 GB","6.5\" LCD, 90 Hz","5000 mAh","13 MP"],"Everyday phone with a big battery at a small price."),
    ("Zenpho","Zenpho Fold Mini",64999,4,["12 GB","512 GB","6.9\" foldable AMOLED","4400 mAh","50 MP + 12 MP"],"Folds to pocket size; opens to a full screen.")]),
  ("Tablets","8471",18,None,["Display","Storage","Battery","Connectivity"],[
    ("Kairo","Kairo Tab 11",21999,20,["11\" 2K LCD","128 GB","8000 mAh","Wi-Fi"],"An 11-inch tablet for study, streaming and notes."),
    ("Voltix","Voltix Pad Kids",9999,30,["8\" HD","32 GB","5100 mAh","Wi-Fi"],"Sturdy tablet with a parental-control mode and a bumper case."),
    ("Zenpho","Zenpho Tab Pro 12.4",54999,6,["12.4\" AMOLED","256 GB","10090 mAh","Wi-Fi + 5G"],"Large AMOLED tablet with a stylus in the box."),
    ("Kairo","Kairo Tab 8 LTE",13999,25,["8.7\" LCD","64 GB","5100 mAh","Wi-Fi + 4G"],"Compact tablet with calling and 4G data.")]),
  ("Mobile Accessories","8544",18,None,["Type","Length / size","Compatibility"],[
    ("Linkr","Linkr Braided USB-C Cable",499,200,["Cable","1.5 m","USB-C devices"],"Nylon-braided cable rated for 60 W charging."),
    ("Linkr","Linkr 33 W Fast Charger",1199,120,["Wall charger","Compact","USB-C, PD 3.0"],"Charges most phones to 50% in about 30 minutes."),
    ("Shieldz","Shieldz Tempered Glass (2-pack)",399,300,["Screen guard","6.6\"","Voltix Nova 5G"],"9H hardness, edge-to-edge, bubble-free fitting."),
    ("Shieldz","Shieldz Clear Case",599,150,["Back cover","6.7\"","Kairo Edge Pro"],"Shock-absorbing corners that stay clear.")]),
  ("Power Banks","8507",18,None,["Capacity","Output","Ports","Weight"],[
    ("Linkr","Linkr 10000 mAh Slim",1299,80,["10000 mAh","22.5 W","USB-C + USB-A","210 g"],"Pocket-sized power bank that charges a phone about twice."),
    ("Linkr","Linkr 20000 mAh Pro",2199,50,["20000 mAh","65 W","2 × USB-C + USB-A","420 g"],"Can charge a laptop as well as phones."),
    ("Voltix","Voltix MagPower 5000",1799,35,["5000 mAh","15 W wireless","USB-C","130 g"],"Magnetic wireless pack that snaps onto the back of a phone."),
    ("Kairo","Kairo Solar 25000",2899,0,["25000 mAh","18 W","2 × USB-A + USB-C","560 g"],"Rugged pack with a solar panel and torch for trips.")]),
 ]),
 ("Computers", "#0ca678", [
  ("Laptops","8471",18,None,["Processor","RAM","Storage","Display","Weight"],[
    ("Kairo","Kairo Book 14",54999,18,["8-core, 4.4 GHz","16 GB","512 GB SSD","14\" 2.2K","1.4 kg"],"Thin, light laptop for work and study, 12-hour battery."),
    ("Voltix","Voltix Gamer 16",104999,6,["14-core, 5.0 GHz + RTX graphics","32 GB","1 TB SSD","16\" 165 Hz","2.3 kg"],"High-refresh gaming laptop with a cool-running chassis."),
    ("Zenpho","Zenpho Air 13",79999,10,["10-core","16 GB","512 GB SSD","13.6\" OLED","1.2 kg"],"OLED ultrabook with an all-day battery."),
    ("Kairo","Kairo Book 15 Essential",38999,25,["6-core, 4.1 GHz","8 GB","512 GB SSD","15.6\" FHD","1.7 kg"],"Dependable laptop for home and office.")]),
  ("Monitors","8528",18,None,["Size","Resolution","Refresh rate","Panel"],[
    ("Kairo","Kairo View 24",9999,30,["24\"","1920 × 1080","75 Hz","IPS"],"Slim-bezel office monitor with an eye-care mode."),
    ("Voltix","Voltix Arena 27Q",24999,12,["27\"","2560 × 1440","165 Hz","IPS"],"Fast QHD monitor for gaming."),
    ("Zenpho","Zenpho Studio 32 4K",44999,5,["32\"","3840 × 2160","60 Hz","IPS, 99% sRGB"],"Colour-accurate 4K screen for design and photos."),
    ("Kairo","Kairo Curve 34",36999,0,["34\" curved","3440 × 1440","100 Hz","VA"],"Ultrawide curved monitor for multitasking.")]),
  ("Keyboards & Mice","8471",18,None,["Type","Connection","Battery","Layout"],[
    ("Typro","Typro K2 Mechanical Keyboard",3499,45,["Mechanical, brown switches","Bluetooth + USB-C","4000 mAh","75%"],"Hot-swappable mechanical keyboard."),
    ("Typro","Typro Silent Mouse",699,120,["Silent click mouse","2.4 GHz wireless","AA, 12 months","—"],"Quiet clicks for shared offices."),
    ("Voltix","Voltix Strike Gaming Mouse",1999,60,["Gaming mouse, 26000 DPI","USB","—","—"],"Light 59 g gaming mouse with an accurate sensor."),
    ("Typro","Typro Combo Wireless",1499,80,["Keyboard + mouse","2.4 GHz wireless","AAA","Full size"],"Keyboard and mouse that share one receiver.")]),
  ("Storage","8523",18,None,["Capacity","Interface","Speed","Type"],[
    ("Storix","Storix 1 TB Portable SSD",6999,40,["1 TB","USB-C 3.2","1050 MB/s","SSD"],"Pocket SSD with drop protection."),
    ("Storix","Storix 2 TB Hard Drive",5499,35,["2 TB","USB 3.0","—","HDD"],"Backup drive for photos and documents."),
    ("Storix","Storix 128 GB Pen Drive",899,200,["128 GB","USB 3.1","150 MB/s","Flash"],"Metal pen drive with a key ring."),
    ("Storix","Storix NVMe 1 TB",5999,25,["1 TB","PCIe 4.0 NVMe","7000 MB/s","Internal SSD"],"Fast internal SSD for upgrades.")]),
 ]),
 ("TV, Audio & Wearables", "#7048e8", [
  ("Televisions","8528",18,None,["Screen","Resolution","Smart OS","Sound"],[
    ("Zenpho","Zenpho 43\" 4K Smart TV",27999,15,["43\"","4K UHD","Android TV","20 W"],"4K smart TV with voice remote."),
    ("Kairo","Kairo 32\" HD Smart TV",12999,30,["32\"","HD Ready","Linux smart","20 W"],"Compact smart TV for bedrooms."),
    ("Zenpho","Zenpho 55\" QLED",49999,8,["55\"","4K QLED","Google TV","40 W Dolby Atmos"],"Bright QLED picture with Dolby Atmos sound."),
    ("Kairo","Kairo 65\" 4K Pro",69999,3,["65\"","4K UHD, 120 Hz","Google TV","50 W"],"Big-screen TV with a 120 Hz panel for sport.")]),
  ("Headphones","8518",18,None,["Type","Noise cancelling","Battery","Connection"],[
    ("Sonix","Sonix Buds Air",1999,150,["True wireless earbuds","ENC calls","30 h with case","Bluetooth 5.3"],"Light earbuds with clear calls."),
    ("Sonix","Sonix Over-Ear ANC",7999,40,["Over-ear","Active noise cancelling","40 h","Bluetooth + 3.5 mm"],"Comfortable headphones that silence travel noise."),
    ("Voltix","Voltix Neckband Pro",1299,90,["Neckband","—","25 h","Bluetooth 5.2"],"Magnetic earbuds on a light neckband."),
    ("Sonix","Sonix Studio Wired",2499,35,["Over-ear, wired","—","—","3.5 mm / 6.3 mm"],"Flat-response headphones for music production.")]),
  ("Speakers","8518",18,None,["Output","Battery","Water resistance","Connection"],[
    ("Sonix","Sonix Boom Mini",1799,70,["10 W","12 h","IPX7","Bluetooth"],"Small speaker that survives the pool."),
    ("Sonix","Sonix Party 80",8999,15,["80 W","15 h, RGB lights","IPX4","Bluetooth + Karaoke mic"],"Party speaker with lights and a microphone."),
    ("Zenpho","Zenpho Soundbar 2.1",11999,12,["160 W","Mains powered","—","HDMI ARC + Bluetooth"],"Soundbar with a wireless subwoofer."),
    ("Kairo","Kairo Smart Speaker",3499,40,["5 W","Mains powered","—","Wi-Fi, voice assistant"],"Voice-controlled speaker for the home.")]),
  ("Smartwatches","8517",18,None,["Display","Battery","Sensors","Water resistance"],[
    ("Voltix","Voltix Fit Watch 3",2499,80,["1.9\" AMOLED","10 days","Heart rate, SpO2","IP68"],"Fitness watch with Bluetooth calling."),
    ("Kairo","Kairo Watch GT",8999,25,["1.43\" AMOLED","14 days","GPS, heart rate, SpO2","5 ATM"],"GPS sports watch with a steel case."),
    ("Zenpho","Zenpho Watch Classic",15999,10,["1.3\" AMOLED","7 days","ECG, heart rate","5 ATM"],"Smartwatch with a classic leather strap."),
    ("Voltix","Voltix Band 7",1499,100,["1.6\" AMOLED","14 days","Heart rate","5 ATM"],"Slim fitness band for steps and sleep.")]),
 ]),
 ("Home Appliances", "#f08c00", [
  ("Air Conditioners","8415",18,None,["Capacity","Star rating","Type","Copper condenser"],[
    ("Coolwave","Coolwave 1.5 T Inverter Split AC",36999,10,["1.5 ton","5 star","Split, inverter","Yes"],"Efficient inverter AC that cools at 52 °C outside."),
    ("Coolwave","Coolwave 1 T Window AC",24999,8,["1 ton","3 star","Window","Yes"],"Simple window AC for small rooms."),
    ("Arctiq","Arctiq 2 T Inverter Split AC",49999,4,["2 ton","3 star","Split, inverter","Yes"],"For large living rooms, with Wi-Fi control."),
    ("Arctiq","Arctiq Portable AC 1 T",29999,0,["1 ton","—","Portable","Yes"],"Moves between rooms; no outdoor unit.")]),
  ("Refrigerators","8418",18,None,["Capacity","Doors","Star rating","Inverter"],[
    ("Arctiq","Arctiq 253 L Frost Free",25999,12,["253 L","Double door","3 star","Yes"],"Frost-free fridge for a family of three."),
    ("Coolwave","Coolwave 190 L Direct Cool",13999,20,["190 L","Single door","4 star","No"],"Single-door fridge with a stabiliser-free range."),
    ("Arctiq","Arctiq 580 L Side-by-Side",69999,3,["580 L","Side by side","—","Yes"],"Large side-by-side fridge with a water dispenser."),
    ("Coolwave","Coolwave 350 L Convertible",36999,7,["350 L","Double door, convertible","3 star","Yes"],"Freezer converts to a fridge when you need space.")]),
  ("Washing Machines","8450",18,None,["Capacity","Type","Star rating","Programs"],[
    ("Washly","Washly 7 kg Front Load",29999,9,["7 kg","Front load","5 star","15"],"Gentle front loader with in-built heater."),
    ("Washly","Washly 8 kg Top Load",19999,14,["8 kg","Top load, fully automatic","5 star","10"],"Fully automatic top loader with a soak mode."),
    ("Coolwave","Coolwave 7.5 kg Semi Automatic",10999,22,["7.5 kg","Semi automatic","5 star","3"],"Twin-tub machine that saves water."),
    ("Washly","Washly 10 kg Washer Dryer",49999,3,["10 kg wash / 6 kg dry","Front load","5 star","16"],"Washes and dries in one go.")]),
  ("Kitchen Appliances","8509",18,None,["Power","Capacity","Type","Warranty"],[
    ("Chefora","Chefora Mixer Grinder 750 W",3999,40,["750 W","3 jars","Mixer grinder","2 years"],"Grinds idli batter, chutneys and masalas."),
    ("Chefora","Chefora Air Fryer 4.2 L",5999,30,["1500 W","4.2 L","Air fryer","1 year"],"Crisp food with a spoon of oil."),
    ("Brewly","Brewly Electric Kettle 1.5 L",1199,90,["1500 W","1.5 L","Kettle","1 year"],"Boils water in three minutes; auto shut-off."),
    ("Chefora","Chefora Induction Cooktop",2499,45,["2000 W","—","Induction","1 year"],"Eight preset Indian menus and a timer.")]),
 ]),
 ("Home & Kitchen", "#e64980", [
  ("Cookware","7323",5,None,["Material","Pieces","Induction base","Dishwasher safe"],[
    ("Tavaa","Tavaa Triply Kadai 24 cm",2199,40,["Triply stainless steel","1","Yes","Yes"],"Even heating for curries and stir-fries."),
    ("Tavaa","Tavaa Cast Iron Tawa",1299,60,["Pre-seasoned cast iron","1","Yes","No"],"Pre-seasoned tawa for dosa and roti."),
    ("Tavaa","Tavaa Pressure Cooker 5 L",2499,35,["Stainless steel","1","Yes","No"],"Five-litre cooker with a safety valve."),
    ("Chefora","Chefora Non-stick Set",3499,25,["Aluminium, non-stick","3","Yes","No"],"Fry pan, kadai and tawa set.")]),
  ("Dinnerware","6912",5,None,["Material","Pieces","Microwave safe","Pattern"],[
    ("Mitti","Mitti Stoneware Dinner Set",3999,20,["Stoneware","18","Yes","Speckled blue"],"Hand-glazed set for six."),
    ("Mitti","Mitti Steel Thali Set",1499,40,["Stainless steel","6","No","Mirror finish"],"Traditional thali with bowls and glass."),
    ("Mitti","Mitti Porcelain Mugs (4)",899,80,["Porcelain","4","Yes","White"],"Classic 350 ml mugs."),
    ("Mitti","Mitti Melamine Kids Set",699,50,["Melamine","5","No","Animals"],"Unbreakable plates and bowls for children.")]),
  ("Storage & Containers","3924",18,None,["Material","Pieces","Capacity","Airtight"],[
    ("Boxa","Boxa Airtight Jars (6)",999,70,["BPA-free plastic","6","1.2 L each","Yes"],"Keeps dal and atta fresh."),
    ("Boxa","Boxa Glass Lunch Boxes (3)",1299,45,["Borosilicate glass","3","640 ml","Yes"],"Oven- and microwave-safe tiffin."),
    ("Boxa","Boxa Steel Masala Box",799,60,["Stainless steel","7 bowls","—","No"],"Spice box with a spoon and glass lid."),
    ("Boxa","Boxa Fridge Organisers (4)",1099,35,["Clear plastic","4","—","No"],"Stackable bins for a tidy fridge.")]),
  ("Home Décor","9105",18,None,["Material","Size","Colour","Mounting"],[
    ("Nookly","Nookly Wooden Wall Clock",1499,30,["Mango wood","30 cm","Natural","Wall"],"Silent sweep clock in solid wood."),
    ("Nookly","Nookly Brass Diya Set",1199,40,["Brass","4 pieces","Gold","Table"],"Hand-crafted diyas for festivals."),
    ("Nookly","Nookly Macramé Wall Hanging",899,25,["Cotton rope","60 × 90 cm","Off-white","Wall"],"Hand-knotted boho hanging."),
    ("Nookly","Nookly Ceramic Vase",749,50,["Ceramic","25 cm","Terracotta","Table"],"Matte vase for dried flowers.")]),
 ]),
 ("Furniture", "#5c940d", [
  ("Office Chairs","9401",18,None,["Back","Adjustments","Max load","Warranty"],[
    ("Sitwell","Sitwell Ergo Mesh Chair",11999,15,["High back, mesh","Lumbar, 3D arms, headrest","120 kg","3 years"],"All-day comfort with adjustable lumbar support."),
    ("Sitwell","Sitwell Task Chair",4999,30,["Mid back","Height, tilt","100 kg","1 year"],"Simple swivel chair for home offices."),
    ("Sitwell","Sitwell Executive Leather",17999,6,["High back, leatherette","Height, tilt lock","130 kg","2 years"],"Padded executive chair."),
    ("Gamr","Gamr Racer Chair",13999,10,["High back, racing style","4D arms, 155° recline","150 kg","2 years"],"Gaming chair with a footrest.")]),
  ("Desks","9403",18,None,["Size","Material","Height","Assembly"],[
    ("Deskly","Deskly Study Table",4999,20,["100 × 50 cm","Engineered wood","75 cm","DIY"],"Study desk with a drawer and shelf."),
    ("Deskly","Deskly Standing Desk",24999,6,["140 × 70 cm","Steel frame, wood top","70–118 cm, motorised","DIY"],"Sit-stand desk with memory presets."),
    ("Deskly","Deskly Corner Desk",7999,8,["120 × 120 cm","Engineered wood","75 cm","DIY"],"L-shaped desk for two monitors."),
    ("Deskly","Deskly Laptop Table",1299,60,["60 × 40 cm","Bamboo","Foldable","None"],"Folding table for working in bed.")]),
  ("Sofas","9401",18,None,["Seats","Upholstery","Frame","Dimensions"],[
    ("Lounjo","Lounjo 3-Seater Fabric Sofa",28999,4,["3","Fabric","Solid wood","198 × 85 cm"],"Firm-seated sofa in grey fabric."),
    ("Lounjo","Lounjo Sofa Cum Bed",22999,5,["3 (opens to bed)","Fabric","Metal","185 × 90 cm"],"Opens into a double bed for guests."),
    ("Lounjo","Lounjo L-Shape Sofa",54999,2,["5","Velvet","Solid wood","250 × 160 cm"],"Large sectional for family rooms."),
    ("Lounjo","Lounjo Single Recliner",19999,6,["1","Leatherette","Metal","90 × 95 cm"],"Manual recliner with a rocking base.")]),
  ("Beds & Mattresses","9404",18,None,["Size","Material","Firmness","Thickness"],[
    ("Snoozr","Snoozr Memory Foam Mattress (Queen)",14999,10,["Queen 78 × 60\"","Memory foam","Medium firm","8\""],"Cooling gel memory foam, 10-year warranty."),
    ("Snoozr","Snoozr Orthopaedic Mattress (Single)",7999,15,["Single 72 × 36\"","High-density foam","Firm","6\""],"Firm support for back pain."),
    ("Snoozr","Snoozr Pillow Pair",1299,60,["Standard","Microfibre","Soft","—"],"Hotel-style pillows, machine washable."),
    ("Deskly","Deskly Queen Bed with Storage",26999,3,["Queen","Engineered wood","—","Hydraulic storage"],"Bed with lift-up storage.")]),
 ]),
 ("Men's Fashion", "#1098ad", [
  ("Men's T-shirts","6109",None,R,["Fabric","Fit","Sleeve","Sizes"],[
    ("Threadly","Threadly Cotton Crew T-shirt",499,200,["100% cotton","Regular","Short","S–XXL"],"Soft everyday T-shirt in eight colours."),
    ("Threadly","Threadly Polo T-shirt",899,120,["Cotton piqué","Slim","Short","S–XXL"],"Classic polo with a two-button placket."),
    ("Urbanx","Urbanx Oversized Graphic Tee",799,90,["Cotton","Oversized","Short","S–XL"],"Drop-shoulder tee with a printed back."),
    ("Urbanx","Urbanx Merino Performance Tee",2999,20,["Merino wool blend","Athletic","Short","M–XL"],"Odour-resistant tee for runs and treks.")]),
  ("Men's Shirts","6205",None,R,["Fabric","Fit","Sleeve","Occasion"],[
    ("Threadly","Threadly Oxford Shirt",1299,80,["Cotton oxford","Slim","Full","Office"],"Crisp shirt that works with jeans or trousers."),
    ("Threadly","Threadly Linen Shirt",2299,40,["Pure linen","Regular","Full","Summer"],"Breathable linen for hot days."),
    ("Urbanx","Urbanx Check Flannel Shirt",1499,50,["Cotton flannel","Regular","Full","Casual"],"Soft brushed checks."),
    ("Regalo","Regalo Silk Festive Kurta Shirt",3499,15,["Art silk","Regular","Full","Festive"],"Festive shirt with a mandarin collar.")]),
  ("Men's Jeans","6203",None,R,["Fabric","Fit","Rise","Sizes"],[
    ("Denimo","Denimo Slim Fit Jeans",1799,90,["Stretch denim","Slim","Mid","28–38"],"Mid-blue slim jeans with stretch."),
    ("Denimo","Denimo Straight Fit Jeans",1499,70,["Denim","Straight","Mid","30–40"],"Classic straight leg."),
    ("Denimo","Denimo Selvedge Jeans",4499,12,["Japanese selvedge denim","Tapered","Mid","30–36"],"Raw selvedge denim that fades with wear."),
    ("Urbanx","Urbanx Cargo Jeans",2199,35,["Denim","Relaxed","Mid","28–36"],"Relaxed jeans with cargo pockets.")]),
  ("Men's Footwear","6404",None,R,["Type","Upper","Sole","Sizes"],[
    ("Stridex","Stridex Running Shoes",2499,60,["Running","Mesh","EVA","UK 6–11"],"Cushioned running shoes, 240 g."),
    ("Stridex","Stridex Leather Formal Shoes",3999,30,["Formal Oxford","Leather","TPR","UK 6–11"],"Polished Oxfords for office."),
    ("Stridex","Stridex Canvas Sneakers",1299,80,["Sneakers","Canvas","Rubber","UK 6–11"],"Everyday canvas sneakers."),
    ("Stridex","Stridex Trek Boots",5499,15,["Hiking boots","Suede + mesh, waterproof","Rubber lug","UK 7–11"],"Waterproof boots with ankle support.")]),
 ]),
 ("Women's Fashion", "#d6336c", [
  ("Kurtas","6204",None,R,["Fabric","Length","Sleeve","Sizes"],[
    ("Rangoli","Rangoli Cotton Straight Kurta",899,120,["Cotton","Calf","Three-quarter","XS–XXL"],"Hand-block printed cotton kurta."),
    ("Rangoli","Rangoli Anarkali Kurta",2499,40,["Rayon","Ankle","Full","S–XXL"],"Flared Anarkali with gota trim."),
    ("Rangoli","Rangoli Kurta Set with Dupatta",3299,25,["Chanderi","Calf","Three-quarter","S–XL"],"Kurta, pants and dupatta set."),
    ("Ethnika","Ethnika Silk Festive Kurta",5499,10,["Silk blend","Knee","Full","S–XL"],"Zari-embroidered festive kurta.")]),
  ("Sarees","5007",5,None,["Fabric","Length","Blouse piece","Work"],[
    ("Ethnika","Ethnika Kanjivaram Silk Saree",12999,6,["Pure silk","6.3 m","Yes","Zari border"],"Rich Kanjivaram weave with a contrast border."),
    ("Ethnika","Ethnika Cotton Handloom Saree",1999,30,["Handloom cotton","6.2 m","Yes","Woven stripes"],"Breathable handloom for daily wear."),
    ("Rangoli","Rangoli Georgette Printed Saree",1299,50,["Georgette","5.5 m","Yes","Printed"],"Light, easy-drape printed saree."),
    ("Ethnika","Ethnika Banarasi Saree",7999,8,["Silk blend","6.3 m","Yes","Banarasi brocade"],"Banarasi brocade for weddings.")]),
  ("Women's Tops","6206",None,R,["Fabric","Fit","Sleeve","Sizes"],[
    ("Bloomz","Bloomz Ribbed Crop Top",599,90,["Cotton rib","Fitted","Short","XS–L"],"Stretchy ribbed crop top."),
    ("Bloomz","Bloomz Linen Shirt Top",1599,40,["Linen blend","Relaxed","Full","S–XL"],"Relaxed linen button-down."),
    ("Bloomz","Bloomz Peplum Top",999,55,["Crepe","Regular","Sleeveless","S–XL"],"Office-ready peplum top."),
    ("Urbanx","Urbanx Satin Party Top",2799,15,["Satin","Regular","Full","XS–L"],"Satin top with a cowl neck.")]),
  ("Handbags","4202",18,None,["Material","Size","Closure","Compartments"],[
    ("Carryo","Carryo Tote Bag",1999,40,["Vegan leather","Large","Magnetic","3"],"Roomy tote that fits a laptop."),
    ("Carryo","Carryo Sling Bag",1299,60,["Vegan leather","Small","Zip","2"],"Cross-body sling for essentials."),
    ("Carryo","Carryo Leather Satchel",5999,10,["Genuine leather","Medium","Buckle","4"],"Structured leather satchel."),
    ("Carryo","Carryo Clutch",899,35,["Embellished fabric","Small","Clasp","1"],"Beaded clutch for evenings.")]),
 ]),
 ("Beauty & Personal Care", "#ae3ec9", [
  ("Skincare","3304",18,None,["Skin type","Quantity","Key ingredient","Fragrance"],[
    ("Glowra","Glowra Vitamin C Serum",699,100,["All","30 ml","10% vitamin C","Free"],"Brightening serum for dull skin."),
    ("Glowra","Glowra Sunscreen SPF 50",499,150,["All","50 g","SPF 50 PA++++","Free"],"Light gel sunscreen, no white cast."),
    ("Glowra","Glowra Ceramide Moisturiser",599,90,["Dry","50 g","Ceramides","Free"],"Barrier-repair cream."),
    ("Glowra","Glowra Niacinamide Face Wash",349,120,["Oily","100 ml","Niacinamide","Mild"],"Oil-control face wash.")]),
  ("Haircare","3305",5,None,["Hair type","Quantity","Key ingredient","Sulphate free"],[
    ("Tressa","Tressa Onion Shampoo",399,120,["All","250 ml","Onion oil","Yes"],"Reduces hair fall with regular use."),
    ("Tressa","Tressa Coconut Hair Oil",249,200,["All","300 ml","Cold-pressed coconut","—"],"Pure cold-pressed coconut oil."),
    ("Tressa","Tressa Keratin Conditioner",449,80,["Frizzy","200 ml","Keratin","Yes"],"Smooths frizz."),
    ("Tressa","Tressa Anti-dandruff Shampoo",349,90,["Dandruff","200 ml","Ketoconazole 1%","No"],"Clinically tested anti-dandruff formula.")]),
  ("Fragrances","3303",18,None,["Type","Quantity","Notes","Longevity"],[
    ("Aurae","Aurae Oud Noir EDP",2499,30,["Eau de parfum","100 ml","Oud, amber","8–10 h"],"Warm oud for evenings."),
    ("Aurae","Aurae Citrus Fresh EDT",1299,45,["Eau de toilette","100 ml","Bergamot, neroli","4–6 h"],"Fresh daytime scent."),
    ("Aurae","Aurae Attar Rose",899,25,["Attar (oil)","12 ml","Rose","10 h+"],"Alcohol-free rose attar."),
    ("Aurae","Aurae Body Mist Trio",799,60,["Body mist","3 × 50 ml","Floral, fruity","2–3 h"],"Three light mists.")]),
  ("Grooming","8510",18,None,["Type","Battery","Attachments","Waterproof"],[
    ("Trimr","Trimr Beard Trimmer",1499,70,["Trimmer","90 min","20 length settings","IPX7"],"Precise trimmer with a titanium blade."),
    ("Trimr","Trimr Hair Clipper Pro",2499,35,["Clipper","120 min","8 combs","No"],"Salon-style clipper."),
    ("Trimr","Trimr Electric Shaver",3999,20,["Rotary shaver","60 min","Pop-up trimmer","IPX7"],"Wet and dry shaving."),
    ("Trimr","Trimr Grooming Kit 7-in-1",2199,40,["Multi-groomer","90 min","7","IPX5"],"Beard, nose, ear and body in one kit.")]),
 ]),
 ("Grocery", "#2f9e44", [
  ("Staples","1101",5,None,["Pack size","Type","Organic","Shelf life"],[
    ("Annam","Annam Whole Wheat Atta 10 kg",499,100,["10 kg","Whole wheat","No","3 months"],"Stone-ground chakki atta."),
    ("Annam","Annam Basmati Rice 5 kg",699,80,["5 kg","Aged basmati","No","12 months"],"Long-grain basmati aged one year."),
    ("Annam","Annam Toor Dal 1 kg",179,150,["1 kg","Unpolished toor dal","No","9 months"],"Unpolished, cooks soft."),
    ("Annam","Annam Organic Moong Dal 1 kg",229,0,["1 kg","Split moong","Yes","9 months"],"Certified organic moong dal.")]),
  ("Snacks","2106",5,None,["Pack size","Type","Vegetarian","Shelf life"],[
    ("Kurkura","Kurkura Aloo Bhujia 400 g",119,200,["400 g","Namkeen","Yes","4 months"],"Crisp Bikaneri-style bhujia."),
    ("Kurkura","Kurkura Roasted Makhana 200 g",249,90,["200 g","Roasted makhana","Yes","6 months"],"Peri-peri roasted fox nuts."),
    ("Kurkura","Kurkura Dark Chocolate 100 g",149,120,["100 g","Chocolate, 70% cocoa","Yes","9 months"],"Dark chocolate with sea salt."),
    ("Kurkura","Kurkura Millet Cookies 250 g",129,100,["250 g","Cookies","Yes","6 months"],"Ragi and jowar cookies, no maida.")]),
  ("Beverages","0902",5,None,["Pack size","Type","Caffeine","Origin"],[
    ("Chaiwala","Chaiwala Assam Tea 500 g",299,120,["500 g","Black tea, CTC","Yes","Assam"],"Strong, malty Assam CTC."),
    ("Chaiwala","Chaiwala Masala Chai 250 g",249,90,["250 g","Tea with spices","Yes","Assam"],"Tea with cardamom, ginger and clove."),
    ("Brewly","Brewly Filter Coffee 500 g",399,70,["500 g","Coffee-chicory 80:20","Yes","Coorg"],"South Indian filter coffee."),
    ("Brewly","Brewly Green Tea (50 bags)",199,100,["50 bags","Green tea","Low","Darjeeling"],"Light green tea bags.")]),
  ("Dry Fruits","0802",5,None,["Pack size","Type","Grade","Origin"],[
    ("Mewa","Mewa California Almonds 500 g",549,80,["500 g","Almonds","Premium","California"],"Crunchy, whole almonds."),
    ("Mewa","Mewa W240 Cashews 500 g",699,60,["500 g","Cashews","W240","Goa"],"Large whole cashews."),
    ("Mewa","Mewa Kashmiri Walnuts 250 g",399,50,["250 g","Walnut kernels","Light halves","Kashmir"],"Light-coloured walnut halves."),
    ("Mewa","Mewa Medjool Dates 400 g",599,0,["400 g","Dates","Jumbo","Jordan"],"Soft, caramel-sweet Medjool dates.")]),
 ]),
]

categories=[]; products=[]
for mi,(main,colour,subs) in enumerate(data):
    msl=slug(main)
    categories.append({"slug":msl,"name":main,"parent":None,"sortOrder":(mi+1)*10,"colour":colour,"gstRule":None,"specKeys":[]})
    for si,(sub,hsn,gst,rule,keys,items) in enumerate(subs):
        ssl=slug(sub)
        categories.append({"slug":ssl,"name":sub,"parent":msl,"sortOrder":(si+1)*10,"colour":colour,"gstRule":rule,"specKeys":keys})
        prefix="".join(w[0] for w in re.findall(r"[A-Za-z]+",sub)).upper()[:3]
        for pi,(brand,name,price,stock,vals,desc) in enumerate(items):
            assert len(vals)==len(keys),(name,vals,keys)
            specs=[{"name":k,"value":v} for k,v in zip(keys,vals) if v!="—"]
            products.append({"sku":f"{prefix}-{slug(brand)[:4].upper()}-{pi+1:02d}{si}{mi}","name":name,"brand":brand,
              "category":ssl,"hsn":hsn,"gstRate":gst if gst is not None else 18,"price":price,"stock":stock,
              "reorderLevel":max(1,min(10,stock//10)),"description":desc+"\n\nSold and shipped by Order Platform. Free delivery; 7-day returns.",
              "specs":specs,"maxPerOrder":5 if price>=20000 else None})

skus=[p["sku"] for p in products]; assert len(skus)==len(set(skus)),"duplicate sku"
assert len(categories)==50,len(categories)
out={"note":"Development catalogue: fictional brands; GST as revised Sept 2025, for demonstration only.",
     "categories":categories,"products":products,
     "existing":{"WIDGET-A":"office-chairs","GADGET-X":"monitors","WIDGET-B":"keyboards-mice"}}
json.dump(out,open(sys.argv[1],"w"),indent=1,ensure_ascii=False)
print(len(categories),"categories,",len(products),"products")
