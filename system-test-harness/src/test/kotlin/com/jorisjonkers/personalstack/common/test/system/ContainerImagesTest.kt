package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContainerImagesTest {
    @Test
    fun `converts configured image tag to Docker image name`() {
        val images = ContainerImages(ImageTags.parse("postgres=postgres:17.2"))

        assertThat(images.imageNameFor("postgres").asCanonicalNameString()).isEqualTo("postgres:17.2")
    }

    @Test
    fun `marks compatible substitute when requested`() {
        val images = ContainerImages(ImageTags.parse("database=example/postgres-compatible:1.0.0"))

        assertThat(
            images
                .imageNameFor("database", "postgres")
                .isCompatibleWith(
                    org.testcontainers.utility.DockerImageName
                        .parse("postgres"),
                ),
        ).isTrue()
    }
}
