package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.purge.PurgeRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * The operator's dial: reads and writes the runtime purge-policy override. Every route requires
 * the ADMIN role (as microservice-security reports it); {@link RequireSignInFilter} already
 * refuses anonymous callers on {@code /admin/**}, this controller narrows to admins.
 *
 * <p>What is dialled here loses to a purge command that CARRIES a rule, and decides every purge
 * that arrives without one — see {@code PurgeUserContent}. So this dial is not only a default for
 * clients that express no preference: it is also the fate of every leaver whose client forgot to
 * state theirs (the memes-ui wizard's preselected option used to, P18 poz. 18). Setting it is a
 * decision about other people's content, which is why it is audited.
 */
@RestController
@RequestMapping("/admin/purge-policy")
class AdminController {

    private final PurgePolicyOverride override;
    private final PurgeRule envDefault;

    AdminController(PurgePolicyOverride override, PurgeRule envDefault) {
        this.override = override;
        this.envDefault = envDefault;
    }

    @GetMapping
    ResponseEntity<?> current(@RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
            required = false) Set<String> roles) {
        if (notAdmin(roles)) {
            return refused();
        }
        var overridden = override.current();
        return ResponseEntity.ok(Map.of(
                "axis", "memes",
                "effective", overridden.orElse(envDefault).asText(),
                "source", overridden.isPresent() ? "DB" : "ENV",
                "envDefault", envDefault.asText()));
    }

    @PutMapping
    ResponseEntity<?> set(@RequestBody Map<String, String> body,
                          @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String caller,
                          @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
                                  required = false) Set<String> roles) {
        if (notAdmin(roles)) {
            return refused();
        }
        String text = body.get("memes");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "MISSING_RULE",
                    "detail", "expected {\"memes\": \"DELETE|ANONYMIZE_AUTHOR|KEEP_POPULAR_ANONYMIZED:n\"}"));
        }
        PurgeRule rule;
        try {
            rule = PurgeRule.parse(text);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_RULE",
                    "detail", invalid.getMessage()));
        }
        override.set(rule, caller);
        return ResponseEntity.ok(Map.of("status", "OVERRIDDEN", "memes", rule.asText()));
    }

    @DeleteMapping
    ResponseEntity<?> clear(@RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String caller,
                            @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
                                    required = false) Set<String> roles) {
        if (notAdmin(roles)) {
            return refused();
        }
        override.clear(caller);
        return ResponseEntity.ok(Map.of("status", "ENV_DEFAULT_RESTORED", "memes", envDefault.asText()));
    }

    private static boolean notAdmin(Set<String> roles) {
        return roles == null || !roles.contains("ADMIN");
    }

    private static ResponseEntity<?> refused() {
        return ResponseEntity.status(403).body(Map.of("status", "NOT_AN_ADMIN",
                "detail", "this dial belongs to administrators"));
    }
}
