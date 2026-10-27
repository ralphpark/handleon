package ai.handleon.car.gleo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SchemaTest {
    @Test fun `schema lists exactly the five functions`() {
        val schema = Json.parseToJsonElement(File("src/main/assets/schema.json").readText()).jsonObject
        assertEquals("ai.handleon.car", schema["packageName"]!!.jsonPrimitive.content)
        val functions = schema["actionCalls"]!!.jsonArray.map { it.jsonObject.getValue("function").jsonObject }
        val expected = setOf("GET_DRIVING_SUMMARY", "GET_PROMISE_STATUS", "GET_CHANGE_STATUS", "MARK_OTHER_DRIVER", "PAUSE_FAMILY_SHARING")
        assertEquals(5, functions.size)
        assertEquals(expected, functions.map { it["name"]!!.jsonPrimitive.content }.toSet())
        functions.forEach {
            assertEquals("service", it["component"]!!.jsonPrimitive.content)
            assertTrue(it["description"]!!.jsonPrimitive.content.endsWith('.'))
            val params = it["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
            val required = params["required"]!!.jsonArray.map { p -> p.jsonPrimitive.content }
            assertEquals(if (it["name"]!!.jsonPrimitive.content == "GET_DRIVING_SUMMARY") listOf("period") else emptyList(), required)
        }
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = manifest.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length).map { receivers.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("android:name") == ".gleo.GleoReceiver" }
        assertEquals("true", receiver.getAttribute("android:exported"))
        val actions = receiver.getElementsByTagName("action")
        assertEquals(expected, (0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttribute("android:name") }.toSet())
    }
}
