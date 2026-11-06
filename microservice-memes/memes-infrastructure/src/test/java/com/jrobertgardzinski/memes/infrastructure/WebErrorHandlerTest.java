package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.application.ObjectStore;
import com.jrobertgardzinski.memes.domain.Meme;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 400/500 split the advice must hold: a broken UPLOAD is the caller's problem (pinned in
 * {@link MemeControllerTest}), but a blob store dying under a GET is OURS — it must answer 500
 * with a generic body, never a 400 blaming the caller, and never the adapter's internal message
 * (which carries object keys and, for the filesystem store, paths).
 */
@Epic("Infrastructure")
@Feature("Error responses")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class WebErrorHandlerTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemeRepository memes;

    @MockitoSpyBean
    ObjectStore objects;

    @Test
    @DisplayName("a store failure while serving a stored meme is a 500 with a generic body, not a 400")
    void store_failure_on_get_is_a_500_not_a_400() throws Exception {
        memes.save(new Meme("sick", SOMEBODY, "png", new byte[]{1, 2, 3}));
        doThrow(new UncheckedIOException("cannot read sick", new IOException("disk I/O error at /var/lib/memes/blobs/sick")))
                .when(objects).get("sick");

        String body = mockMvc.perform(get("/memes/{id}", "sick"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("STORAGE_ERROR"))
                .andExpect(jsonPath("$.detail").value("internal storage error"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("/var/lib"), "internal paths must not leak to the wire");
        assertFalse(body.contains("cannot read"), "nor the adapter's raw message");
    }
}
