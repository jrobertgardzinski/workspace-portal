package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.TagMeme;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The tagging boundary: the author curates their meme's tag set (PUT-like replace via POST),
 * everyone reads it. Search rides on the gallery listing ({@code GET /memes?tag=...}).
 */
@RestController
@RequestMapping("/memes/{memeId}/tags")
class TagController {

    record TagsRequest(List<String> tags) {}

    private final TagMeme tagMeme;
    private final TagRepository tagRepository;

    TagController(TagMeme tagMeme, TagRepository tagRepository) {
        this.tagMeme = tagMeme;
        this.tagRepository = tagRepository;
    }

    @PostMapping
    ResponseEntity<?> tag(@PathVariable("memeId") String memeId,
                          @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER_ID)
                          com.jrobertgardzinski.identity.UserId caller,
                          @RequestBody TagsRequest request) {
        if (request.tags() == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "TAGS_REQUIRED"));
        }
        TagMeme.Result result;
        try {
            result = tagMeme.execute(memeId, caller, request.tags());
        } catch (IllegalArgumentException illegalTag) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "INVALID_TAG", "detail", illegalTag.getMessage()));
        }
        return switch (result.status()) {
            case TAGGED -> ResponseEntity.ok(Map.of("tags",
                    result.tags().stream().map(Tag::value).sorted().toList()));
            case NO_SUCH_MEME -> ResponseEntity.notFound().build();
            case NOT_THE_AUTHOR -> ResponseEntity.status(403).body(Map.of("status", "NOT_THE_AUTHOR",
                    "detail", "the uploader curates the tags of their own meme"));
            case TOO_MANY_TAGS -> ResponseEntity.badRequest().body(Map.of("status", "TOO_MANY_TAGS"));
        };
    }

    @GetMapping
    List<String> tags(@PathVariable("memeId") String memeId) {
        return tagRepository.tagsOf(memeId).stream().map(Tag::value).sorted().toList();
    }
}
