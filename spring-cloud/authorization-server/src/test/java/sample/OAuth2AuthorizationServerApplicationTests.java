/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.hamcrest.core.Is.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link OAuth2AuthorizationServerApplication}
 *
 * @author Josh Cummings
 */
@SpringBootTest(properties = {
    "spring.config.location=classpath:/auth-test-application.yml",
    "eureka.client.enabled=false",
    "spring.cloud.config.enabled=false",
    "spring.cloud.compatibility-verifier.enabled=false",
    "AUTH_WRITER_CLIENT_SECRET=secret-writer",
    "AUTH_READER_CLIENT_SECRET=secret-reader",
    "AUTH_DEMO_USER=test-user",
    "AUTH_DEMO_PASSWORD=test-password",
    "AUTH_FULFILLMENT_CLIENT_SECRET=test-fulfillment-client-secret",
    "AUTH_RETURNS_CLIENT_SECRET=test-returns-client-secret",
    "AUTH_MODERATOR_CLIENT_SECRET=test-review-moderator-secret",
    "AUTH_ISSUER_URI=http://localhost:9999"
})
@AutoConfigureMockMvc
class OAuth2AuthorizationServerApplicationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void requestTokenUsingClientCredentialsGrantType() throws Exception {

		String base64Credentials = Base64.getEncoder().encodeToString("writer:secret-writer".getBytes());
		this.mvc.perform(post("/oauth2/token")
						.param("grant_type", "client_credentials")
						.header("Authorization", "Basic " + base64Credentials))
				.andExpect(status().isOk());
	}

	@Test
	void dedicatedMockFulfillmentClientUsesClientCredentials() throws Exception {
		String credentials = Base64.getEncoder().encodeToString(
			"mock-fulfillment:test-fulfillment-client-secret".getBytes());
		this.mvc.perform(post("/oauth2/token")
				.param("grant_type", "client_credentials")
				.param("scope", "fulfillment:read fulfillment:write")
				.header("Authorization", "Basic " + credentials))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.access_token").exists());
	}

	@Test
	void mockReturnsOperatorHasDedicatedScopes() throws Exception {
		String credentials = Base64.getEncoder().encodeToString(
			"mock-returns:test-returns-client-secret".getBytes());
		this.mvc.perform(post("/oauth2/token")
				.param("grant_type", "client_credentials")
				.param("scope", "returns:read returns:manage")
				.header("Authorization", "Basic " + credentials))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.access_token").exists());
	}

	@Test
	void requestOpenidConfiguration() throws Exception {

		this.mvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk());
	}

	@Test
	void requestJwkSet() throws Exception {

		this.mvc.perform(get("/oauth2/jwks"))
				.andExpect(status().isOk());
	}

	@Test
	void healthy() throws Exception {
		this.mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("UP")));
	}

}