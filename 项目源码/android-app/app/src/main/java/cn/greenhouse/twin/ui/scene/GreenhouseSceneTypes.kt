package cn.greenhouse.twin.ui.scene

enum class GreenhouseViewPreset(val mappingKey: String, val title: String) {
    COMPLETE("complete", "完整"),
    OVERVIEW("overview", "概览"),
    INTERIOR("interior", "内部"),
    TOP("top", "俯视"),
    DETAIL("detail", "泵细节"),
}

enum class SceneObject(val businessId: String, val displayName: String, val anchorName: String) {
    PUMP("pump", "水泵", "Anchor_Pump"),
    LAMP("lamp", "补光灯", "Anchor_Lamp"),
    FAN("fan", "风扇", "Anchor_Fan"),
    TEMPERATURE("temperature", "温度", "Anchor_Temperature"),
    HUMIDITY("humidity", "空气湿度", "Anchor_Humidity"),
    LIGHT("lightLevel", "光照", "Anchor_LightLevel"),
    SOIL("soilHumidity", "土壤", "Anchor_SoilHumidity"),
    CONTROLLER("controller", "ESP32", "Anchor_Controller"),
}
