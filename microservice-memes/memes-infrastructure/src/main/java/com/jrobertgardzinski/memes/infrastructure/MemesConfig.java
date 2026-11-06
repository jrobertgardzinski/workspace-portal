package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.application.CastVote;
import com.jrobertgardzinski.memes.application.ListMemes;
import com.jrobertgardzinski.memes.application.MakeThumbnail;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.application.PublishMeme;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.application.RankMemes;
import com.jrobertgardzinski.memes.application.SearchMemesByTag;
import com.jrobertgardzinski.memes.application.TagMeme;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.application.ShowMemeScores;
import com.jrobertgardzinski.memes.application.ShowMemeVote;
import com.jrobertgardzinski.memes.application.ViewMeme;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.memes.config.ImageLimits;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.memes.config.RateLimit;
import com.jrobertgardzinski.memes.config.TagLimits;
import com.jrobertgardzinski.memes.config.ThumbnailSize;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free use cases and image optimiser as Spring beans; the repository is a
 * {@code @Component} discovered by scanning.
 */
@Configuration
class MemesConfig {

    @Bean
    ImageLimits imageLimits(@Value("${memes.image.max-dimension:1024}") int maxDimension) {
        return new ImageLimits(maxDimension);
    }

    @Bean
    WebImageOptimizer webImageOptimizer(ImageLimits imageLimits,
                                        @Value("${memes.decode.concurrency:3}") int decodeConcurrency) {
        // the guard is infrastructure (a JVM-wide semaphore sized by an env dial), so it wraps
        // the pure optimizer here — the memes-image module stays free of concurrency policy
        return new ConcurrencyGuardedImageOptimizer(new WebImageOptimizer(imageLimits), imageLimits,
                decodeConcurrency, java.time.Duration.ofSeconds(5));
    }

    @Bean
    UploadAdmission uploadAdmission(
            @Value("${memes.upload.concurrency:8}") int uploadConcurrency) {
        // Sized WITH the container's memory, not guessed: 8 x the 10 MB multipart ceiling is 80 MB
        // of raw uploads at worst, which fits beside the decode budget inside the heap that
        // k8s/base/memes.yaml grants. It used to be Tomcat's default thread count — 200 — which at
        // the same 10 MB is 2000 MB against a ~1075 MiB heap.
        return new UploadAdmission(uploadConcurrency, java.time.Duration.ofSeconds(5));
    }

    @Bean
    ThumbnailSize thumbnailSize(@Value("${memes.image.thumbnail-max-dimension:256}") int maxDimension) {
        return new ThumbnailSize(maxDimension);
    }

    @Bean
    MakeThumbnail makeThumbnail(MemeRepository repository,
                                com.jrobertgardzinski.memes.application.ObjectStore objectStore,
                                WebImageOptimizer optimizer, ThumbnailSize thumbnailSize) {
        return new MakeThumbnail(repository, objectStore, optimizer, thumbnailSize);
    }

    @Bean
    PublishMeme publishMeme(WebImageOptimizer optimizer, MemeRepository repository, MemeContentIndex contentIndex,
                            com.jrobertgardzinski.memes.application.ObjectStore objectStore) {
        return new PublishMeme(optimizer, repository, contentIndex, objectStore);
    }

    @Bean
    com.jrobertgardzinski.authors.AuthorDirectory authorDirectory(
            @Value("${security.url}") String securityUrl,
            @Value("${memes.author-names.cache-seconds:60}") long cacheSeconds,
            java.time.Clock clock) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("memes.author-names");
        return com.jrobertgardzinski.authors.SecurityAuthorDirectory.overHttp(securityUrl,
                new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Duration.ofSeconds(cacheSeconds), clock,
                failure -> log.warn("author names unavailable, showing content without them: {}", failure.toString()));
    }

    @Bean
    ViewMeme viewMeme(MemeRepository repository) {
        return new ViewMeme(repository);
    }

    @Bean
    com.jrobertgardzinski.memes.application.ServeMeme serveMeme(
            MemeRepository repository,
            com.jrobertgardzinski.memes.application.ObjectStore objectStore,
            com.jrobertgardzinski.memes.application.ImageEncoder imageEncoder) {
        return new com.jrobertgardzinski.memes.application.ServeMeme(repository, objectStore, imageEncoder);
    }

    @Bean
    RateLimit uploadRate(@Value("${memes.upload.rate-limit-per-minute:12}") int perMinute,
                         java.time.Clock clock) {
        return new RateLimit(perMinute, clock);   // the shared clock bean, same as the hot ranking
    }

    @Bean
    com.jrobertgardzinski.memes.application.FlagMeme flagMeme(
            MemeRepository memeRepository, com.jrobertgardzinski.memes.application.ContentFlags contentFlags) {
        return new com.jrobertgardzinski.memes.application.FlagMeme(memeRepository, contentFlags);
    }

    @Bean
    com.jrobertgardzinski.memes.system.DeleteMeme deleteMeme(
            MemeRepository memeRepository, VoteRepository voteRepository, MemeContentIndex contentIndex,
            TagRepository tagRepository, com.jrobertgardzinski.memes.domain.MemeEvents memeEvents,
            org.springframework.transaction.support.TransactionTemplate tx) {
        // the transactional decorator, not the plain use case: the DB part of a teardown is atomic
        // (the use case itself stays framework-free — the seam lives here, in infrastructure)
        return new TransactionalDeleteMeme(
                memeRepository, voteRepository, contentIndex, tagRepository, memeEvents, tx);
    }

    @Bean
    TagLimits tagLimits(@Value("${memes.tags.max-per-meme:8}") int maxPerMeme) {
        return new TagLimits(maxPerMeme);
    }

    @Bean
    TagMeme tagMeme(MemeRepository repository, TagRepository tagRepository, TagLimits tagLimits) {
        return new TagMeme(repository, tagRepository, tagLimits);
    }

    @Bean
    SearchMemesByTag searchMemesByTag(MemeRepository repository, TagRepository tagRepository) {
        return new SearchMemesByTag(repository, tagRepository);
    }

    @Bean
    ListMemes listMemes(MemeRepository repository) {
        return new ListMemes(repository);
    }

    @Bean
    CastVote castVote(MemeRepository memeRepository, VoteRepository voteRepository) {
        return new CastVote(memeRepository, voteRepository);
    }

    @Bean
    ShowMemeVote showMemeVote(MemeRepository memeRepository, VoteRepository voteRepository) {
        return new ShowMemeVote(memeRepository, voteRepository);
    }

    @Bean
    ShowMemeScores showMemeScores(MemeRepository memeRepository, VoteRepository voteRepository) {
        return new ShowMemeScores(memeRepository, voteRepository);
    }

    @Bean
    com.jrobertgardzinski.memes.config.ErasureTolerance erasureTolerance(
            @Value("${memes.erasure.stuck-after-seconds:1800}") long stuckAfterSeconds) {
        return new com.jrobertgardzinski.memes.config.ErasureTolerance(
                java.time.Duration.ofSeconds(stuckAfterSeconds));
    }

    @Bean
    com.jrobertgardzinski.memes.system.WatchErasureBacklog watchErasureBacklog(
            com.jrobertgardzinski.memes.domain.MemeErasure erasure,
            com.jrobertgardzinski.memes.config.ErasureTolerance tolerance,
            com.jrobertgardzinski.observation.Observations<com.jrobertgardzinski.memes.domain.Observation> observations,
            java.time.Clock clock) {
        return new com.jrobertgardzinski.memes.system.WatchErasureBacklog(
                erasure, tolerance, observations, clock);
    }

    @Bean
    PurgeRule defaultMemesPurgeRule(@Value("${memes.purge.memes:DELETE}") String rule) {
        return PurgeRule.parse(rule);
    }

    @Bean
    PurgeUserContent purgeUserContent(MemeRepository memeRepository,
                                      com.jrobertgardzinski.memes.domain.MemeErasure erasure,
                                      VoteRepository voteRepository,
                                      MemeContentIndex contentIndex, TagRepository tagRepository,
                                      MemeEvents memeEvents,
                                      com.jrobertgardzinski.memes.domain.PurgePolicyOverride override,
                                      PurgeRule defaultMemesPurgeRule,
                                      org.springframework.transaction.support.TransactionTemplate tx) {
        // transactional decorator — a purge that dies halfway must not strand half the leaver's memes
        return new TransactionalPurgeUserContent(memeRepository, erasure, voteRepository, contentIndex,
                tagRepository, memeEvents, override, defaultMemesPurgeRule, tx);
    }

    /**
     * The saga's reversible step and its inverse. Neither gets a transactional decorator of its own:
     * both are driven exclusively by {@code PurgeCommandsListener}, which already opens ONE
     * transaction per command so the work and the outbox row that reports it commit together —
     * a second template inside it would only nest a participation in the same transaction.
     */
    @Bean
    com.jrobertgardzinski.memes.system.MarkUserContentForErasure markUserContentForErasure(
            com.jrobertgardzinski.memes.domain.MemeErasure erasure, java.time.Clock clock) {
        return new com.jrobertgardzinski.memes.system.MarkUserContentForErasure(erasure, clock);
    }

    @Bean
    com.jrobertgardzinski.memes.system.RestoreUserContent restoreUserContent(
            com.jrobertgardzinski.memes.domain.MemeErasure erasure) {
        return new com.jrobertgardzinski.memes.system.RestoreUserContent(erasure);
    }


    @Bean
    RankMemes rankMemes(VoteRepository voteRepository, java.time.Clock clock) {
        return new RankMemes(voteRepository, clock);
    }

    @Bean
    java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
}

