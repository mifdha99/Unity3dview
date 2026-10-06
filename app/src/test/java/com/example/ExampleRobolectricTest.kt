package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.viewer.manager.FileManager
import com.example.viewer.manager.GlbExporter
import com.example.viewer.manager.MaterialManager
import com.example.viewer.manager.ModelImportResult
import com.example.viewer.manager.ModelImporter
import com.example.viewer.manager.TextureManager
import com.example.viewer.manager.UnityPackageParser
import com.example.viewer.model.SupportStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verify app_name and core 3D pipeline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Unity 3D Viewer", appName)

        val fileManager = FileManager(context)
        val textureManager = TextureManager()
        val materialManager = MaterialManager()
        val importer = ModelImporter(textureManager, materialManager)
        val packageParser = UnityPackageParser(context, fileManager)
        val glbExporter = GlbExporter(context)

        val sampleDir = fileManager.ensureSampleAssetsDir()
        val glbFile = File(sampleDir, "UnityRover_Animated.glb")
        assertTrue(glbFile.exists())

        val glbResult = importer.importModelFile(glbFile)
        assertTrue(glbResult is ModelImportResult.Success)
        val model = (glbResult as ModelImportResult.Success).model
        assertTrue(model.vertexCount > 0)
        assertTrue(model.triangleCount > 0)
        assertEquals(4, model.animations.size)

        val exportedBytes = glbExporter.serializeToGlbBytes(model)
        assertTrue(exportedBytes.size > 100)

        val pkgFile = File(sampleDir, "CyberStation_StarterKit.unitypackage")
        val pkgReport = packageParser.parseUnityPackage(pkgFile, pkgFile.name)
        assertTrue(pkgReport.entries.isNotEmpty())
        assertTrue(pkgReport.entries.any { it.supportStatus == SupportStatus.SUPPORTED })
        assertTrue(pkgReport.entries.any { it.supportStatus == SupportStatus.PARTIALLY_SUPPORTED })
        assertTrue(pkgReport.entries.any { it.supportStatus == SupportStatus.NOT_SUPPORTED })
    }
}
