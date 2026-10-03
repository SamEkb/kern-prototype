package com.siams.kern_prototype.data.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R20: файл секретов исключён из облачной копии, переноса на другое устройство (API 31+) и
 * полного резервного копирования (API ≤ 30); прочие данные этими правилами не исключаются (Q14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

    private val secretsPath = "datastore/$SECRETS_STORE_NAME.preferences_pb"

    private fun xml(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/$name.xml"))

    private fun excludes(section: org.w3c.dom.Element): List<Pair<String, String>> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as org.w3c.dom.Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

    @Test
    fun dataExtractionRulesExcludeOnlySecrets() {
        val doc = xml("data_extraction_rules")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val sections = doc.getElementsByTagName(tag)
            assertEquals("раздел $tag", 1, sections.length)
            assertEquals(tag, listOf("file" to secretsPath), excludes(sections.item(0) as org.w3c.dom.Element))
            assertEquals("$tag: без include (остальное — по умолчанию)", 0,
                (sections.item(0) as org.w3c.dom.Element).getElementsByTagName("include").length)
        }
    }

    @Test
    fun fullBackupContentExcludesOnlySecrets() {
        val root = xml("backup_rules").documentElement
        assertEquals("full-backup-content", root.tagName)
        assertEquals(listOf("file" to secretsPath), excludes(root))
        assertEquals(0, root.getElementsByTagName("include").length)
    }

    /** Путь в правилах совпадает с фактическим файлом хранилища секретов (как его строит делегат). */
    @Test
    fun ruleMatchesTheActualSecretsFile() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val actual = context.preferencesDataStoreFile(SECRETS_STORE_NAME)
        assertEquals(secretsPath, actual.relativeTo(context.filesDir).path)
    }

    @Test
    fun manifestUsesTheseRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}
