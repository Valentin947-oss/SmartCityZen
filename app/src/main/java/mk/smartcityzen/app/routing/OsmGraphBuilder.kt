package mk.smartcityzen.app.routing

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "OsmGraphBuilder"

/**
 * Builds a [PedestrianGraph] for a bounding box by querying the public Overpass API
 * for ways a wheelchair user would actually travel on: footways, sidewalks, paths,
 * pedestrian streets and marked crossings. This is the "digital twin of the street
 * network" that AccessibleRouteFinder then routes over.
 *
 * NOTE: the public Overpass instance is rate-limited and meant for prototyping.
 * For production, self-host Overpass (or pre-process a Bitola .pbf extract into the
 * bundled Room DB at build time) so routing works offline and isn't rate-limited.
 */
class OsmGraphBuilder(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val overpassUrls = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    // Full Bitola city area — trimmed slightly from full municipality (which included
    // farmland) to keep the Overpass query light enough for the public server to
    // finish without timing out.
    private val defaultBBox = BBox(south = 41.000, west = 21.280, north = 41.060, east = 21.390)

    data class BBox(val south: Double, val west: Double, val north: Double, val east: Double)

    suspend fun buildGraph(bbox: BBox = defaultBBox): PedestrianGraph = withContext(Dispatchers.IO) {
        // Includes dedicated footways AND ordinary streets pedestrians actually walk
        // along (residential/tertiary/service/unclassified) — most streets in Bitola
        // don't have their sidewalks mapped as separate OSM ways, so routing only on
        // "footway"-tagged ways left huge gaps in the network. Motorways/trunk/track
        // are excluded (not pedestrian-relevant, and "track" pulls in a lot of rural
        // farm-road data that isn't needed and was contributing to server timeouts).
        val query = """
            [out:json][timeout:50];
            (
              way["highway"~"^(footway|path|pedestrian|living_street|steps|residential|tertiary|secondary|unclassified|service)$"]
                 ["access"!="private"]
                 (${bbox.south},${bbox.west},${bbox.north},${bbox.east});
              way["highway"="crossing"](${bbox.south},${bbox.west},${bbox.north},${bbox.east});
            );
            (._;>;);
            out body;
        """.trimIndent()

        for ((index, url) in overpassUrls.withIndex()) {
            val graph = tryFetchGraph(url, query)
            if (!graph.isEmpty) return@withContext graph
            Log.e(TAG, "Mirror ${index + 1}/${overpassUrls.size} ($url) returned no usable data" +
                if (index < overpassUrls.size - 1) " — trying next mirror" else "")
        }
        Log.e(TAG, "All Overpass mirrors failed or returned empty data")
        PedestrianGraph()
    }

    private fun tryFetchGraph(overpassUrl: String, query: String): PedestrianGraph {
        val requestBody = FormBody.Builder()
            .add("data", query)
            .build()

        val request = Request.Builder()
            .url(overpassUrl)
            .header("User-Agent", "SmartCityZen/1.0 (mk.smartcityzen.app)")
            .post(requestBody)
            .build()

        val graph = PedestrianGraph()
        Log.d(TAG, "Sending Overpass request to $overpassUrl")

        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Overpass response code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Overpass request failed: ${response.code} ${response.message}")
                    return graph
                }
                val body = response.body?.string() ?: run {
                    Log.e(TAG, "Overpass response body was null")
                    return graph
                }
                Log.d(TAG, "Overpass response body length: ${body.length}")
                val root = JsonParser.parseString(body).asJsonObject
                val elements = root.getAsJsonArray("elements") ?: run {
                    Log.e(TAG, "Overpass response had no 'elements' array")
                    return graph
                }
                Log.d(TAG, "Overpass returned ${elements.size()} elements")

                val nodeCoords = HashMap<Long, Pair<Double, Double>>()
                for (el in elements) {
                    val obj = el.asJsonObject
                    if (obj.get("type").asString == "node") {
                        val id = obj.get("id").asLong
                        val lat = obj.get("lat").asDouble
                        val lon = obj.get("lon").asDouble
                        nodeCoords[id] = lat to lon
                        graph.addNode(GraphNode(id, lat, lon))
                    }
                }
                for (el in elements) {
                    val obj = el.asJsonObject
                    if (obj.get("type").asString != "way") continue
                    val tags = obj.getAsJsonObject("tags")
                    val highwayTag = tags?.get("highway")?.asString ?: "footway"
                    val wayNodes = obj.getAsJsonArray("nodes")?.map { it.asLong } ?: continue

                    for (i in 0 until wayNodes.size - 1) {
                        val a = wayNodes[i]
                        val b = wayNodes[i + 1]
                        val coordA = nodeCoords[a] ?: continue
                        val coordB = nodeCoords[b] ?: continue
                        val dist = haversineMeters(coordA.first, coordA.second, coordB.first, coordB.second)
                        graph.addEdge(a, b, dist, highwayTag)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Overpass request to $overpassUrl threw an exception", e)
        }
        Log.d(TAG, "Graph built from $overpassUrl: ${graph.nodes.size} nodes, ${graph.adjacency.size} adjacency entries")
        return graph
    }
}
