package mk.smartcityzen.app.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import mk.smartcityzen.app.model.AccessibilityReport
import java.util.PriorityQueue

data class RouteResult(
    val points: List<GraphNode>,
    val totalDistanceMeters: Double,
    val obstaclesAvoided: Int
)

/**
 * Finds the best wheelchair/low-mobility route between two points using A* search,
 * where each edge's cost is its physical length multiplied by a penalty derived from
 * nearby crowdsourced reports (pitch deck: "најдобра траса ... каде нема дупки, тротоарите
 * се ниски"). A verified ramp *lowers* the cost of an edge so the router prefers it;
 * an unramped high curb or reported pothole raises it so the router avoids it unless
 * there is truly no other way through.
 */
class AccessibleRouteFinder(private val graph: PedestrianGraph) {

    /** Reports within this radius of an edge's midpoint are considered to affect that edge.
     *  Widened from an original 12m — real-world report taps and OSM edge geometry are
     *  rarely perfectly aligned, so a tight radius meant almost no edges ever got
     *  penalized and the router routed straight through reported obstacles. */
    private val influenceRadiusMeters = 30.0

    suspend fun findRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        reports: List<AccessibilityReport>
    ): RouteResult? = withContext(Dispatchers.Default) {
        if (graph.isEmpty) return@withContext null
        val start = graph.nearestNode(startLat, startLon) ?: return@withContext null
        val goal = graph.nearestNode(endLat, endLon) ?: return@withContext null

        applyAccessibilityWeights(reports)

        val openSet = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        val cameFrom = HashMap<Long, Long>()
        val gScore = HashMap<Long, Double>().withDefault { Double.MAX_VALUE }
        val obstaclesOnEdge = HashMap<Long, Int>() // node -> obstacles avoided reaching it

        gScore[start.osmId] = 0.0
        openSet.add(start.osmId to heuristic(start, goal))
        val visited = HashSet<Long>()

        while (openSet.isNotEmpty()) {
            val (currentId, _) = openSet.poll()
            if (currentId == goal.osmId) break
            if (!visited.add(currentId)) continue
            val current = graph.nodes[currentId] ?: continue

            for (edge in graph.neighbors(currentId)) {
                val tentative = gScore.getValue(currentId) + edge.weight
                if (tentative < gScore.getValue(edge.to)) {
                    cameFrom[edge.to] = currentId
                    gScore[edge.to] = tentative
                    val penalized = edge.weight > edge.distanceMeters * 1.2
                    obstaclesOnEdge[edge.to] = (obstaclesOnEdge[currentId] ?: 0) + if (penalized) 1 else 0
                    val neighborNode = graph.nodes[edge.to]
                    if (neighborNode != null) {
                        openSet.add(edge.to to (tentative + heuristic(neighborNode, goal)))
                    }
                }
            }
        }

        if (!cameFrom.containsKey(goal.osmId) && start.osmId != goal.osmId) return@withContext null

        // Reconstruct path
        val path = mutableListOf(goal.osmId)
        var cur = goal.osmId
        while (cur != start.osmId) {
            cur = cameFrom[cur] ?: break
            path.add(cur)
        }
        path.reverse()

        val nodePath = path.mapNotNull { graph.nodes[it] }
        val totalDistance = nodePath.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lon, b.lat, b.lon) }

        RouteResult(
            points = nodePath,
            totalDistanceMeters = totalDistance,
            obstaclesAvoided = obstaclesOnEdge[goal.osmId] ?: 0
        )
    }

    /** Recomputes every edge's weight from scratch so old reports never linger after being resolved. */
    private fun applyAccessibilityWeights(reports: List<AccessibilityReport>) {
        var penalizedEdges = 0
        var totalEdges = 0
        for (edges in graph.adjacency.values) {
            for (edge in edges) {
                totalEdges++
                val fromNode = graph.nodes[edge.from] ?: continue
                val toNode = graph.nodes[edge.to] ?: continue
                val midLat = (fromNode.lat + toNode.lat) / 2
                val midLon = (fromNode.lon + toNode.lon) / 2

                var multiplier = 1.0
                for (report in reports) {
                    val dist = haversineMeters(midLat, midLon, report.lat, report.lng)
                    if (dist <= influenceRadiusMeters) {
                        multiplier *= report.type.basePenalty
                    }
                }
                edge.weight = edge.distanceMeters * multiplier
                if (multiplier != 1.0) penalizedEdges++
            }
        }
        Log.d("AccessibleRouteFinder", "Applied ${reports.size} reports: $penalizedEdges/$totalEdges edges penalized (radius ${influenceRadiusMeters}m)")
    }

    private fun heuristic(a: GraphNode, b: GraphNode): Double = haversineMeters(a.lat, a.lon, b.lat, b.lon)
}
