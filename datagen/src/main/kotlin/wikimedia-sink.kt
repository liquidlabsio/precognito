import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.*
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.sse.SseEventSource


fun main(){

    val output = "datagen/target/wikimedia"

    val fileName = output + "-now.log";

    val wikiMediaUrl = "https://stream.wikimedia.org/v2/stream/recentchange"
    val client = ClientBuilder.newClient()
    val target = client.target(wikiMediaUrl)

    println("Path" +File(fileName).absolutePath)

    var count = 0
    val objectMapper = ObjectMapper()


  File(fileName).bufferedWriter().use { out ->
        try {
            SseEventSource.target(target).build().use { eventSource ->
                eventSource.register { inboundSseEvent ->
                    val readData = inboundSseEvent.readData()
                    if (readData.isNotEmpty()) {
                        val readValue = objectMapper.readValue(readData, HashMap::class.java)
                        val isBot = readValue.get("bot") as Boolean
                        if (!isBot) {
                                out.write(readData)
                                out.newLine()
                        }
                    }
                    println("entry----" + count++)
                }
                eventSource.open()

                //Consuming events for one minute
                Thread.sleep(1 * 60 * 1000.toLong())
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (t: Throwable) {
            t.printStackTrace()
        }

    }
    client.close()
    
}
