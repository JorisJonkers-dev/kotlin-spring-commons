package com.jorisjonkers.personalstack.common.test.system

import org.testcontainers.utility.DockerImageName

class ContainerImages(
    private val imageTags: ImageTags,
) {
    fun imageNameFor(service: String): DockerImageName =
        DockerImageName.parse(imageTags.requireTagFor(service))

    fun imageNameFor(
        service: String,
        compatibleSubstituteFor: String,
    ): DockerImageName =
        imageNameFor(service).asCompatibleSubstituteFor(compatibleSubstituteFor)
}
