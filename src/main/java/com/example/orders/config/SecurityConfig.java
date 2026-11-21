package com.example.orders.config;

import com.example.orders.security.KeycloakRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.Collection;
import java.util.List;

/**
 * Keycloak signs users in; this app only checks the tokens it issues (an OAuth2 resource server).
 * spring.security.oauth2.resourceserver.jwt.issuer-uri names the realm, and the signing keys are
 * fetched from it. Roles come from the token's realm roles.
 *
 * Every API needs a token except the web page, its Keycloak settings, and the health and
 * Prometheus endpoints read by the platform's own tooling. Ownership (a customer seeing only
 * their own orders) is checked in OrderAccessService: URL rules cannot see whose order it is.
 */
@Configuration
public class SecurityConfig {

    private static final String STREAM_PATH = "/api/stream/orders";

    /**
     * The LRA coordinator calls /lra-participant to compensate a saga and cannot send a token,
     * so those calls are accepted only from these networks. A call that gets through still acts
     * only on an order the named saga holds (Order.ownedBy).
     */
    @Value("${orders.saga.lra-callers}")
    private List<String> lraCallers;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   com.example.orders.security.CurrentUser currentUser,
                                                   com.example.orders.service.AccountStatusService accounts) throws Exception {
        List<IpAddressMatcher> coordinatorNetworks = lraCallers.stream().map(IpAddressMatcher::new).toList();
        AuthorizationManager<RequestAuthorizationContext> fromCoordinator = (authentication, context) ->
                new AuthorizationDecision(coordinatorNetworks.stream().anyMatch(net -> net.matches(context.getRequest())));
        http
            // Tokens travel in a header, never a cookie, so there is no cross-site request to forge
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // The web app's files and screens are public; the API behind them checks every call
                .requestMatchers(SecurityConfig::isWebAppRequest).permitAll()
                .requestMatchers("/error").permitAll()
                // The GraphQL catalogue is read-only and public, like /api/public; GraphiQL only in development
                .requestMatchers("/graphql", "/graphiql", "/graphiql/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.PUT, "/lra-participant/**").access(fromCoordinator)
                .requestMatchers("/actuator/**", "/api/admin/**", "/api/dlq/**").hasRole("ADMIN")
                .requestMatchers("/api/orders/csv", "/api/orders/xml").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/orders/**").hasRole("ADMIN")
                .requestMatchers("/api/fulfillment/**").hasAnyRole("ADMIN", "SERVICE")
                .requestMatchers("/api/delivery/**").hasRole("DELIVERY")
                .requestMatchers("/api/orders", "/api/orders/**", "/api/invoices/**").hasAnyRole("CUSTOMER", "ADMIN")
                .requestMatchers("/api/returns", "/api/returns/**").hasAnyRole("CUSTOMER", "ADMIN")
                .requestMatchers("/api/wishlist", "/api/wishlist/**").hasRole("CUSTOMER")
                .requestMatchers("/api/cart", "/api/cart/**").hasRole("CUSTOMER")
                .requestMatchers("/api/reviews", "/api/reviews/**").hasRole("CUSTOMER")
                .requestMatchers("/api/checkout/**").hasRole("CUSTOMER")
                .requestMatchers("/api/wallet", "/api/wallet/**").hasRole("CUSTOMER")
                // Views and clicks on shop banners: shoppers only, so staff browsing does not skew the counts
                .requestMatchers(HttpMethod.POST, "/api/promotions/*/view", "/api/promotions/*/click").hasRole("CUSTOMER")
                .requestMatchers("/api/delivery-ratings/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            .oauth2ResourceServer(server -> server
                .bearerTokenResolver(bearerTokenResolver())
                .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromRealm())));
        // After the token is checked: a user an admin turned off gets 403 on everything but /api/me
        http.addFilterAfter(new com.example.orders.security.InactiveAccountFilter(currentUser, accounts),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** A GET for the web app: anything outside the API, actuator and the saga coordinator's callbacks. */
    static boolean isWebAppRequest(jakarta.servlet.http.HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return WebAppConfig.SERVER_PATHS.stream().noneMatch(p -> path.startsWith("/" + p) || path.equals("/" + p.substring(0, p.length() - 1)));
    }

    private static JwtAuthenticationConverter rolesFromRealm() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new java.util.ArrayList<>();
            KeycloakRoles.of(jwt).forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())));
            return authorities;
        });
        return converter;
    }

    /**
     * Browsers cannot set a header on an EventSource, so the live stream alone also accepts
     * the token as ?access_token=. Everywhere else it must be in the Authorization header.
     */
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> {
            String token = request.getParameter("access_token");
            if (token != null && STREAM_PATH.equals(request.getRequestURI())) {
                return token;
            }
            return header.resolve(request);
        };
    }
}
