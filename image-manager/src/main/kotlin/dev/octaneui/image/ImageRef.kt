package dev.octaneui.image

import dev.octaneui.core.OctaneException
import dev.octaneui.core.ErrorCode

/** OCI image reference: registry/repository:tag or @digest. */
data class ImageRef(val registry: String, val repository: String, val reference: String) {

    val registryBaseUrl: String
        get() = when (registry) {
            "docker.io", "index.docker.io", "registry-1.docker.io" -> "https://registry-1.docker.io"
            else -> "https://$registry"
        }

    companion object {
        fun parse(input: String): ImageRef {
            var s = input.trim()
            if (s.isEmpty()) throw OctaneException(ErrorCode.EMPTY_IMAGE_REF, "Empty image reference")
            var reference = "latest"
            val at = s.indexOf('@')
            if (at >= 0) {
                reference = s.substring(at + 1); s = s.substring(0, at)
            } else {
                val colon = s.lastIndexOf(':')
                if (colon > s.lastIndexOf('/')) { reference = s.substring(colon + 1); s = s.substring(0, colon) }
            }
            val first = s.substringBefore('/')
            val hasRegistry = s.contains('/') && (first.contains('.') || first.contains(':') || first == "localhost")
            val registry = if (hasRegistry) first else "docker.io"
            var repo = if (hasRegistry) s.substringAfter('/') else s
            if (registry == "docker.io" && !repo.contains('/')) repo = "library/$repo"
            if (repo.isEmpty()) throw OctaneException(ErrorCode.NO_REPOSITORY, "No repository in: $input", listOf(input))
            return ImageRef(registry, repo, reference)
        }
    }
}
