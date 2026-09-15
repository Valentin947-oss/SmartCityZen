package mk.smartcityzen.app.model

/** How the reporting citizen moves through the city — affects which obstacles matter most to them. */
enum class MobilityType(val label: String) {
    WALKING("Пешак"),
    WHEELCHAIR("Инвалидска количка"),
    STROLLER("Количка за бебе"),
    SCOOTER("Тротинет");
}
