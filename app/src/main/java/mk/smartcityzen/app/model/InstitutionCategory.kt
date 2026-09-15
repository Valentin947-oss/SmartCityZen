package mk.smartcityzen.app.model

enum class InstitutionCategory(val label: String) {
    GOVERNMENT("Општина / државна установа"),
    HEALTH("Здравствена установа"),
    EDUCATION("Училиште / факултет"),
    BANK("Банка"),
    PHARMACY("Аптека"),
    POST("Пошта"),
    RETAIL("Продавница"),
    HOSPITALITY("Кафе / ресторан / хотел"),
    CULTURE("Културна установа"),
    OTHER("Друго");
}

enum class EntranceType(val label: String) {
    RAMP("Рампа"),
    LEVEL("Рамен влез (без скали)"),
    STEPS_NO_RAMP("Скали, нема рампа"),
    UNKNOWN("Не сум сигурен/на");
}
