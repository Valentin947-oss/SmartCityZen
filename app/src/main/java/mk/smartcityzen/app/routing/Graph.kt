package mk.smartcityzen.app.routing

import kotlin.math.*

/** A point on the pedestrian network — corresponds to an OSM node. */
data class GraphNode(
    val osmId: Long,
    val lat: Double,
    val lon: Double
)

/**
 * A walkable segment between two nodes (a piece of a footway/sidewalk/crossing).
 * [weight] starts as the physical distance in meters and gets multiplied by
 * accessibility penalties before every route request (see AccessibleRouteFinder).
 */
data class GraphEdge(
    val from: Long,
    val to: Long,
    val distanceMeters: Double,
    var weight: Double = distanceMeters,
    val highwayTag: String = "footway" // footway, path, crossing, sidewalk...
)

class PedestrianGraph {
    val nodes = HashMap<Long, GraphNode>()
    val adjacency = HashMap<Long, MutableList<GraphEdge>>()

    fun addNode(node: GraphNode) {
        nodes.putIfAbsent(node.osmId, node)
    }

    /** Adds the edge in both directions — pedestrian ways are walkable either way. */
    fun addEdge(a: Long, b: Long, distanceMeters: Double, highwayTag: String) {
        adjacency.getOrPut(a) { mutableListOf() }.add(GraphEdge(a, b, distanceMeters, distanceMeters, highwayTag))
        adjacency.getOrPut(b) { mutableListOf() }.add(GraphEdge(b, a, distanceMeters, distanceMeters, highwayTag))
    }

    fun neighbors(nodeId: Long): List<GraphEdge> = adjacency[nodeId] ?: emptyList()

    /** Nearest graph node to an arbitrary tap on the map (used to snap start/end points). */
    fun nearestNode(lat: Double, lon: Double): GraphNode? =
        nodes.values.minByOrNull { haversineMeters(it.lat, it.lon, lat, lon) }

    val isEmpty: Boolean get() = nodes.isEmpty()
}

/** Great-circle distance in meters — accurate enough at city scale. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}
