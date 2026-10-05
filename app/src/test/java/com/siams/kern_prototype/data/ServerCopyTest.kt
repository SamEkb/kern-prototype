package com.siams.kern_prototype.data

import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** R04/R06: когда предупреждать о серверной копии и что говорят тексты предупреждений. */
class ServerCopyTest {

    private fun layer(remote: String? = null, stale: String? = null) = LayerEntity(
        id = "l", tableId = "t", lampType = LampType.DAYLIGHT, filePath = "/f", cameraProfileJson = "{}",
        capturedAt = 0, remoteImageId = remote, staleRemoteImageId = stale,
    )

    @Test
    fun layerHasServerCopyIfEverSent() {
        assertTrue("отправлен", layer(remote = "img-1").hasServerCopy())
        assertTrue("переснят после отправки: прежний кадр на сервере", layer(stale = "img-0").hasServerCopy())
        assertFalse("не отправлялся", layer().hasServerCopy())
    }

    private val notices = listOf(
        "sent_layer_reshoot_message", "sent_layer_delete_note", "capture_reshoot_sent_hint", "send_queue_clear_note",
    )

    private fun strings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as org.w3c.dom.Element
            e.getAttribute("name") to e.textContent
        }
    }

    /** Тексты честно объясняют ограничения: не утверждают ни потерю, ни гарантированное сохранение. */
    @Test
    fun noticesNeitherClaimLossNorGuaranteedSafety() {
        val all = strings()
        for (name in notices) {
            val text = all[name] ?: error("нет строки $name")
            val lower = text.lowercase()
            for (claim in listOf("потерян", "утерян", "гарантирован", "надёжно сохран", "точно сохран", "безопасно удал")) {
                assertFalse("$name утверждает «$claim»: $text", lower.contains(claim))
            }
            assertTrue("$name: подсказка проверить сервер — $text", lower.contains("провер"))
            assertTrue("$name: речь о S900 — $text", text.contains("S900"))
        }
    }
}
