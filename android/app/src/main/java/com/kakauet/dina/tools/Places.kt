package com.kakauet.dina.tools

import java.text.Normalizer
import java.time.ZoneId

/**
 * Cities and countries Dina knows the time of, by their Spanish name. A country with several
 * zones takes the one of its capital or largest city (Estados Unidos → Nueva York).
 */
object Places {
    data class Place(val name: String, val zone: ZoneId)

    private val TABLE: Map<String, Place> = buildMap {
        fun add(zone: String, vararg names: String) = names.forEach { put(fold(it), Place(it, ZoneId.of(zone))) }
        // Spain and Europe
        add("Europe/Madrid", "Madrid", "España", "Barcelona", "Valencia", "Sevilla", "Bilbao", "Zaragoza", "Málaga", "Palma", "Mallorca", "Ibiza")
        add("Atlantic/Canary", "Canarias", "Islas Canarias", "Las Palmas", "Gran Canaria", "Tenerife", "Santa Cruz de Tenerife", "Lanzarote")
        add("Europe/London", "Londres", "Reino Unido", "Inglaterra", "Escocia", "Edimburgo", "Mánchester", "Liverpool")
        add("Europe/Dublin", "Dublín", "Irlanda")
        add("Europe/Lisbon", "Lisboa", "Portugal", "Oporto")
        add("Europe/Paris", "París", "Francia", "Marsella")
        add("Europe/Berlin", "Berlín", "Alemania", "Múnich", "Fráncfort", "Hamburgo")
        add("Europe/Rome", "Roma", "Italia", "Milán", "Venecia", "Florencia", "Nápoles")
        add("Europe/Amsterdam", "Ámsterdam", "Países Bajos", "Holanda")
        add("Europe/Brussels", "Bruselas", "Bélgica")
        add("Europe/Vienna", "Viena", "Austria")
        add("Europe/Zurich", "Zúrich", "Suiza", "Ginebra")
        add("Europe/Athens", "Atenas", "Grecia")
        add("Europe/Stockholm", "Estocolmo", "Suecia")
        add("Europe/Oslo", "Oslo", "Noruega")
        add("Europe/Copenhagen", "Copenhague", "Dinamarca")
        add("Europe/Helsinki", "Helsinki", "Finlandia")
        add("Europe/Warsaw", "Varsovia", "Polonia")
        add("Europe/Prague", "Praga", "Chequia", "República Checa")
        add("Europe/Budapest", "Budapest", "Hungría")
        add("Europe/Bucharest", "Bucarest", "Rumanía", "Rumania")
        add("Europe/Kyiv", "Kiev", "Ucrania")
        add("Europe/Moscow", "Moscú", "Rusia")
        add("Europe/Istanbul", "Estambul", "Turquía")
        add("Europe/Andorra", "Andorra")
        // America
        add("America/New_York", "Nueva York", "Estados Unidos", "Washington", "Miami", "Boston", "Filadelfia", "Orlando")
        add("America/Chicago", "Chicago", "Texas", "Houston", "Dallas")
        add("America/Denver", "Denver")
        add("America/Los_Angeles", "Los Ángeles", "San Francisco", "California", "Las Vegas", "Seattle")
        add("Pacific/Honolulu", "Hawái", "Honolulu")
        add("America/Toronto", "Toronto", "Canadá", "Montreal")
        add("America/Vancouver", "Vancouver")
        add("America/Mexico_City", "Ciudad de México", "México", "CDMX", "Guadalajara", "Monterrey", "Puebla")
        add("America/Cancun", "Cancún")
        add("America/Tijuana", "Tijuana")
        add("America/Guatemala", "Guatemala")
        add("America/El_Salvador", "El Salvador", "San Salvador")
        add("America/Tegucigalpa", "Honduras", "Tegucigalpa")
        add("America/Managua", "Nicaragua", "Managua")
        add("America/Costa_Rica", "Costa Rica", "San José")
        add("America/Panama", "Panamá")
        add("America/Havana", "Cuba", "La Habana")
        add("America/Santo_Domingo", "República Dominicana", "Santo Domingo", "Punta Cana")
        add("America/Puerto_Rico", "Puerto Rico", "San Juan")
        add("America/Bogota", "Bogotá", "Colombia", "Medellín", "Cali", "Cartagena", "Barranquilla")
        add("America/Caracas", "Caracas", "Venezuela", "Maracaibo")
        add("America/Guayaquil", "Ecuador", "Quito", "Guayaquil")
        add("America/Lima", "Lima", "Perú", "Cuzco")
        add("America/La_Paz", "Bolivia", "La Paz", "Santa Cruz")
        add("America/Santiago", "Santiago de Chile", "Chile", "Valparaíso")
        add("America/Argentina/Buenos_Aires", "Buenos Aires", "Argentina", "Rosario", "Mendoza")
        add("America/Montevideo", "Montevideo", "Uruguay")
        add("America/Asuncion", "Asunción", "Paraguay")
        add("America/Sao_Paulo", "São Paulo", "Sao Paulo", "Brasil", "Río de Janeiro", "Brasilia")
        // Africa, Asia and Oceania
        add("Africa/Casablanca", "Marruecos", "Rabat", "Casablanca", "Marrakech", "Tánger")
        add("Africa/Cairo", "El Cairo", "Egipto")
        add("Africa/Lagos", "Nigeria", "Lagos")
        add("Africa/Nairobi", "Kenia", "Nairobi")
        add("Africa/Johannesburg", "Sudáfrica", "Johannesburgo", "Ciudad del Cabo")
        add("Asia/Jerusalem", "Israel", "Jerusalén", "Tel Aviv")
        add("Asia/Dubai", "Dubái", "Emiratos Árabes", "Abu Dabi")
        add("Asia/Kolkata", "India", "Nueva Delhi", "Delhi", "Bombay")
        add("Asia/Bangkok", "Tailandia", "Bangkok")
        add("Asia/Singapore", "Singapur")
        add("Asia/Jakarta", "Indonesia", "Yakarta")
        add("Asia/Manila", "Filipinas", "Manila")
        add("Asia/Shanghai", "China", "Pekín", "Beijing", "Shanghái")
        add("Asia/Hong_Kong", "Hong Kong")
        add("Asia/Seoul", "Corea", "Corea del Sur", "Seúl")
        add("Asia/Tokyo", "Japón", "Tokio", "Osaka", "Kioto")
        add("Australia/Sydney", "Australia", "Sídney")
        add("Australia/Melbourne", "Melbourne")
        add("Pacific/Auckland", "Nueva Zelanda", "Auckland")
    }

    /** The place a model wrote ("londres", "la Habana", "en Tokio"), or null if Dina does not know it. */
    fun find(text: String): Place? {
        val key = fold(text).removePrefix("en ").trim()
        return TABLE[key] ?: TABLE[key.removePrefix("la ").removePrefix("el ")] ?: TABLE["el $key"] ?: TABLE["la $key"]
    }

    private fun fold(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
