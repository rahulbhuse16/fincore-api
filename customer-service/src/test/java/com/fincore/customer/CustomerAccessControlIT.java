package com.fincore.customer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spring Security, validation, problem-details format, and customer-level authorization (requires Docker for PostgreSQL). */
@SpringBootTest(properties = "fincore.security.jwt-secret=integration-test-secret-integration-test-secret")
@AutoConfigureMockMvc
@Testcontainers
class CustomerAccessControlIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired MockMvc mvc;

    private static RequestPostProcessor customer(UUID customerId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of("CUSTOMER")).claim("customer_id", customerId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin-1").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static final String BODY = """
            {"fullName":"Asha Rao","email":"%s","phone":"+919876543210","dateOfBirth":"1990-05-01",
             "documentType":"PASSPORT","documentNumber":"ABCDE1234F"}""";

    private String create(UUID id, String email) throws Exception {
        return mvc.perform(post("/api/v1/customers").with(customer(id)).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(email)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.documentLast4").value("****234F")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anonymousRequestsAreRejectedWithProblemJson() throws Exception {
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID())).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void aCustomerCanReadOnlyTheirOwnProfile() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        create(alice, "alice@example.test");
        create(bob, "bob@example.test");
        mvc.perform(get("/api/v1/customers/" + alice).with(customer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/" + alice).with(customer(bob))).andExpect(status().isNotFound()); // not 403: no enumeration
        mvc.perform(get("/api/v1/customers/" + alice).with(admin())).andExpect(status().isOk());
    }

    @Test
    void onlyAdminsCanChangeKycStatus() throws Exception {
        UUID carol = UUID.randomUUID();
        create(carol, "carol@example.test");
        mvc.perform(patch("/api/v1/customers/" + carol + "/kyc").with(customer(carol)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REJECTED\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/customers/" + carol + "/kyc").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REJECTED\",\"reason\":\"sanctions hit\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("REJECTED"));
        mvc.perform(patch("/api/v1/customers/" + carol + "/kyc").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}")).andExpect(status().isConflict()); // REJECTED -> VERIFIED is not allowed
    }

    @Test
    void invalidInputIsReportedFieldByFieldWithoutLeakingInternals() throws Exception {
        mvc.perform(post("/api/v1/customers").with(customer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"\",\"email\":\"not-an-email\",\"dateOfBirth\":\"2999-01-01\",\"documentType\":\"X\",\"documentNumber\":\"1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void internalEndpointsRequireTheServiceRole() throws Exception {
        UUID dave = UUID.randomUUID();
        create(dave, "dave@example.test");
        mvc.perform(get("/internal/v1/customers/" + dave + "/kyc-status").with(customer(dave))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/customers/" + dave + "/kyc-status")
                .with(jwt().jwt(j -> j.subject("account-service").claim("roles", List.of("SERVICE"))).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    void duplicateProfileCreationIsAConflict() throws Exception {
        UUID erin = UUID.randomUUID();
        create(erin, "erin@example.test");
        mvc.perform(post("/api/v1/customers").with(customer(erin)).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("erin2@example.test")))
                .andExpect(status().isConflict());
    }
}
