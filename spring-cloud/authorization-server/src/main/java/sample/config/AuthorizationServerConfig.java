//CHECKSTYLE:OFF
/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.util.matcher.RequestMatcher;
import sample.jose.SigningKeyLoader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;


@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationServerConfig.class);

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {

    // Replaced this call with the implementation of applyDefaultSecurity() to be able to add a custom redirect_uri validator
    // OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
      new OAuth2AuthorizationServerConfigurer();

    // Register a custom redirect_uri validator, that allows redirect uris based on https://localhost during development
    authorizationServerConfigurer
      .authorizationEndpoint(authorizationEndpoint ->
        authorizationEndpoint
          .authenticationProviders(configureAuthenticationValidator())
      );

    RequestMatcher endpointsMatcher = authorizationServerConfigurer
      .getEndpointsMatcher();

    http
      .securityMatcher(endpointsMatcher)
      .authorizeHttpRequests(authorize ->
        authorize.anyRequest().authenticated()
      )
      .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
      .apply(authorizationServerConfigurer);

    http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
      .oidc(Customizer.withDefaults()); // Enable OpenID Connect 1.0

    http
      .exceptionHandling(exceptions ->
        exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
      )
      .oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);

    return http.build();
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "auth.identity", name = "store", havingValue = "demo", matchIfMissing = true)
  public RegisteredClientRepository registeredClientRepository(
      @Value("${AUTH_WRITER_CLIENT_SECRET}") String writerClientSecret,
      @Value("${AUTH_READER_CLIENT_SECRET}") String readerClientSecret,
      @Value("${AUTH_FULFILLMENT_CLIENT_SECRET}") String fulfillmentClientSecret,
      @Value("${AUTH_RETURNS_CLIENT_SECRET}") String returnsClientSecret,
      @Value("${AUTH_MODERATOR_CLIENT_SECRET}") String moderatorClientSecret,
      @Value("${AUTH_DEMO_USER}") String demoUser,
      @Value("${AUTH_DEMO_PASSWORD}") String demoPassword,
      @Value("${AUTH_SECURITY_STRICT:false}") boolean strictMode,
      PasswordEncoder passwordEncoder) {
    return buildDemoClientTemplates(writerClientSecret, readerClientSecret,
        fulfillmentClientSecret, returnsClientSecret, moderatorClientSecret,
        demoUser, demoPassword, strictMode, passwordEncoder);
  }

  static RegisteredClientRepository buildDemoClientTemplates(
      String writerClientSecret, String readerClientSecret,
      String fulfillmentClientSecret, String returnsClientSecret,
      String moderatorClientSecret, String demoUser, String demoPassword,
      boolean strictMode, PasswordEncoder passwordEncoder) {
    AuthCredentialPolicy.validate(strictMode, demoUser, demoPassword,
        writerClientSecret, readerClientSecret, fulfillmentClientSecret,
        returnsClientSecret, moderatorClientSecret);
    RegisteredClient writerClient = RegisteredClient.withId(UUID.randomUUID().toString())
      .clientId("writer")
      .clientSecret(passwordEncoder.encode(writerClientSecret))
      .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
      .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
      .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
      .redirectUri("https://my.redirect.uri")
      .redirectUri("https://localhost:8443/openapi/webjars/swagger-ui/oauth2-redirect.html")
      .scope(OidcScopes.OPENID)
      .scope("product:read")
      .scope("product:write")
      .scope("cart:read")
      .scope("cart:write")
      .scope("wishlist:read")
      .scope("wishlist:write")
      .scope("review:read")
      .scope("review:write")
      .scope("review:report")
      .scope("order:read")
      .scope("order:write")
      .scope("return:read")
      .scope("return:write")
      .scope("inventory:read")
      .scope("inventory:write")
      .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
      .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(1)).build())
      .build();

    RegisteredClient readerClient = RegisteredClient.withId(UUID.randomUUID().toString())
      .clientId("reader")
      .clientSecret(passwordEncoder.encode(readerClientSecret))
      .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
      .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
      .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
      .redirectUri("https://my.redirect.uri")
      .redirectUri("https://localhost:8443/openapi/webjars/swagger-ui/oauth2-redirect.html")
      .scope(OidcScopes.OPENID)
      .scope("product:read")
      .scope("cart:read")
      .scope("wishlist:read")
      .scope("review:read")
      .scope("review:report")
      .scope("order:read")
      .scope("return:read")
      .scope("inventory:read")
      .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
      .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(1)).build())
      .build();

    // Separate service identity: only this client can update mock shipment
    // milestones. Customer/writer client tokens have no fulfillment:write scope.
    RegisteredClient fulfillmentClient = RegisteredClient.withId(UUID.randomUUID().toString())
      .clientId("mock-fulfillment")
      .clientSecret(passwordEncoder.encode(fulfillmentClientSecret))
      .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
      .scope("fulfillment:read")
      .scope("fulfillment:write")
      .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build())
      .build();

        // Restricted mock returns operator, separate from customer and fulfillment clients.
    RegisteredClient returnsClient = RegisteredClient.withId(UUID.randomUUID().toString())
      .clientId("mock-returns")
      .clientSecret(passwordEncoder.encode(returnsClientSecret))
      .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
      .scope("returns:read")
      .scope("returns:manage")
      .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build())
      .build();

        // Dedicated operator only. No customer token gets review:moderate.
    RegisteredClient moderatorClient = RegisteredClient.withId(UUID.randomUUID().toString())
      .clientId("mock-review-moderator")
      .clientSecret(passwordEncoder.encode(moderatorClientSecret))
      .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
      .scope("review:moderate")
      .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build())
      .build();

    return new InMemoryRegisteredClientRepository(writerClient, readerClient, fulfillmentClient, returnsClient, moderatorClient);

  }

  @Bean
  public JWKSource<SecurityContext> jwkSource(
      @Value("${AUTH_JWT_KEYSTORE_PATH:}") String keystorePath,
      @Value("${AUTH_JWT_KEYSTORE_PASSWORD:}") String storePassword,
      @Value("${AUTH_JWT_KEY_ALIAS:}") String keyAlias,
      @Value("${AUTH_JWT_KEY_PASSWORD:}") String keyPassword,
      @Value("${AUTH_REQUIRE_PERSISTENT_KEYS:false}") boolean requirePersistent,
      @Value("${AUTH_SECURITY_STRICT:false}") boolean strictMode,
      @Value("${auth.identity.store:demo}") String identityStore) {
    RSAKey rsaKey = SigningKeyLoader.loadOrGenerate(keystorePath, storePassword,
        keyAlias, keyPassword, requirePersistent || strictMode || "jdbc".equals(identityStore));
    JWKSet jwkSet = new JWKSet(rsaKey);
    return (jwkSelector, securityContext) -> jwkSelector.select(jwkSet);
  }

  @Bean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }

  @Bean
  public AuthorizationServerSettings authorizationServerSettings(
      @Value("${AUTH_ISSUER_URI:http://auth-server:9999}") String issuer) {
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }

  private Consumer<List<AuthenticationProvider>> configureAuthenticationValidator() {
    return (authenticationProviders) ->
      authenticationProviders.forEach((authenticationProvider) -> {
        if (authenticationProvider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider) {
          Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> authenticationValidator =
            // Override default redirect_uri validator
            new CustomRedirectUriValidator()
              // Reuse default scope validator
              .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR);

          ((OAuth2AuthorizationCodeRequestAuthenticationProvider) authenticationProvider)
            .setAuthenticationValidator(authenticationValidator);
        }
      });
  }

  static class CustomRedirectUriValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext authenticationContext) {
      OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequestAuthentication =
        authenticationContext.getAuthentication();
      RegisteredClient registeredClient = authenticationContext.getRegisteredClient();
      String requestedRedirectUri = authorizationCodeRequestAuthentication.getRedirectUri();

      LOG.trace("Will validate the redirect uri {}", requestedRedirectUri);

      // Use exact string matching when comparing client redirect URIs against pre-registered URIs
      if (!registeredClient.getRedirectUris().contains(requestedRedirectUri)) {
        LOG.trace("Redirect uri is invalid!");
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST);
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
      }
      LOG.trace("Redirect uri is OK!");
    }
  }
}
//CHECKSTYLE:ON
