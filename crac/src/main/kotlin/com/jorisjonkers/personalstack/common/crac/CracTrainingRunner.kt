package com.jorisjonkers.personalstack.common.crac

import org.crac.Core
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.event.EventListener
import org.springframework.web.client.RestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Build-time JIT warm-up driver that finishes by triggering a CRaC checkpoint.
 *
 * Activated via `crac.train.enabled=true` (typically combined with the
 * `crac-train` Spring profile). Hits each configured endpoint locally
 * `iterations` times to drive C1/C2 compilation through the request pipeline,
 * then calls `Core.checkpointRestore()`. The JVM dumps to the directory
 * configured by `-XX:CRaCCheckpointTo=...` and exits 137; the checkpoint
 * files are then COPYed into the final image so a fresh pod restores in
 * <1 s with the hot path already JIT-compiled.
 *
 * Lives in kotlin-common rather than per-service because every Spring
 * service in this monorepo benefits identically — only the endpoint list
 * (declared in each service's `application-crac-train.yml`) needs to differ.
 */
class CracTrainingRunner(
    private val properties: CracTrainingProperties,
    private val restClientBuilder: RestClient.Builder = RestClient.builder(),
    private val checkpointInvoker: () -> Unit = { Core.checkpointRestore() },
) : ApplicationListener<ApplicationReadyEvent> {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val resolvedPort = AtomicInteger(0)

    @EventListener
    fun onWebServerInitialized(event: WebServerInitializedEvent) {
        resolvedPort.set(event.webServer.port)
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        runOnce(resolvedPort.get())
    }

    /**
     * Drive warmup against `port` and (optionally) trigger the checkpoint.
     * Public so tests can invoke without standing up a Spring web server.
     */
    fun runOnce(port: Int) {
        if (port == 0) {
            logger.warn("CRaC training: no server port resolved (no embedded web server?); skipping warmup")
            return
        }
        if (properties.endpoints.isEmpty()) {
            logger.warn("CRaC training: crac.train.endpoints is empty; skipping warmup")
            return
        }
        runWarmup(port)
        if (properties.checkpointAfterWarmup) {
            logger.info("CRaC training: invoking Core.checkpointRestore()")
            checkpointInvoker()
        } else {
            logger.info("CRaC training: warmup complete, crac.train.checkpoint-after-warmup=false; not checkpointing")
        }
    }

    private fun runWarmup(port: Int) {
        val baseUrl = "http://localhost:$port"
        val client = restClientBuilder.baseUrl(baseUrl).build()
        logger.info(
            "CRaC training: warming {} endpoints x {} iterations against {}",
            properties.endpoints.size,
            properties.iterations,
            baseUrl,
        )
        properties.endpoints.forEach { path ->
            var ok = 0
            var failed = 0
            repeat(properties.iterations) {
                runCatching {
                    client
                        .get()
                        .uri(path)
                        .retrieve()
                        .toBodilessEntity()
                }.onSuccess { ok++ }
                    .onFailure { failed++ }
            }
            logger.info("CRaC training: warmed {} ({} ok, {} non-2xx/error)", path, ok, failed)
        }
    }
}
