package me.rhunk.snapenhance.core.util

import me.rhunk.snapenhance.common.BuildConfig
import me.rhunk.snapenhance.core.ModContext
import me.rhunk.snapenhance.core.bridge.BridgeClient
import java.io.File
import java.util.zip.ZipFile

object LSPatchUpdater {
    private const val TAG = "LSPatchUpdater"

    private fun getModuleUniqueHash(module: ZipFile): String {
        return module.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.crc }
            .reduce { acc, crc -> acc xor crc }
            .toString(16)
    }

    fun onBridgeConnected(context: ModContext, bridgeClient: BridgeClient) {
        val embeddedModule = context.androidContext.cacheDir
            .resolve("lspatch")
            .resolve(BuildConfig.APPLICATION_ID).let { moduleDir ->
                if (!moduleDir.exists()) return@let null
                moduleDir.listFiles()?.firstOrNull { it.extension == "apk" }
            } ?: return

        context.log.verbose("Found embedded SE at ${embeddedModule.absolutePath}", TAG)

        val seAppApk = File(bridgeClient.getApplicationApkPath()).also {
            if (!it.canRead()) {
                throw IllegalStateException("Cannot read SnapEnhance apk")
            }
        }

        runCatching {
            if (getModuleUniqueHash(ZipFile(embeddedModule)) == getModuleUniqueHash(ZipFile(seAppApk))) {
                context.log.verbose("Embedded SE is up to date", TAG)
                return
            }
        }.onFailure {
            throw IllegalStateException("Failed to compare module signature", it)
        }

        context.log.verbose("updating", TAG)
        context.shortToast("Updating SnapEnhance. Please wait...")
        // copy embedded module to cache
        runCatching {
            seAppApk.copyTo(embeddedModule, overwrite = true)
        }.onFailure {
            seAppApk.delete()
            context.log.error("Failed to copy embedded module", it, TAG)
            context.longToast("Failed to update SnapEnhance. Please check logcat for more details.")
            context.forceCloseApp()
            return
        }

        context.longToast("SnapEnhance updated!")
        context.log.verbose("updated", TAG)
        context.forceCloseApp()
    }
}