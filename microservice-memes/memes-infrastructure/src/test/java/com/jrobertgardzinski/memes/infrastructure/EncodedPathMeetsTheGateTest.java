package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate and the router must read the same path.
 *
 * <p>Spring matches a handler against the DECODED segments of the request path, so
 * {@code /%61dmin/purge-policy} is dispatched to {@link AdminController} — while a gate that tests
 * the RAW URI for an "/admin" prefix reads it as "not ours" and lets it through with nobody
 * resolved and, worse, with its own {@code admin} flag false. Everything under /admin requires a
 * signed-in ADMIN, reads included, and that requirement lived entirely in a prefix test the
 * attacker chose the spelling of.
 *
 * <p>The comment threads' gate had the identical defect and was fixed the same way on the same day;
 * neither service had a correct filter in the estate to copy, which is why this test exists in both.
 */
@Epic("Infrastructure")
@Feature("Sign-in gate")
@Story("Percent-encoded paths")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class EncodedPathMeetsTheGateTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("an anonymous read of the admin dial on a percent-encoded path is refused by the gate")
    void the_gate_sees_what_the_router_sees() throws Exception {
        // "%61" is "a": the dispatcher decodes it and hands the request to AdminController
        URI encoded = URI.create("/%61dmin/purge-policy");

        // 401 and not 403: nobody was resolved at all, which is what the gate is for. A 200 here
        // would mean the dial answered an anonymous caller; a 500 would mean the request reached
        // a handler that then fell over, which is luck rather than a guard.
        mockMvc.perform(get(encoded)).andExpect(status().isUnauthorized());
    }
}
