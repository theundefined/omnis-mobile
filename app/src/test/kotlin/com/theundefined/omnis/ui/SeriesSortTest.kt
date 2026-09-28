package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesSortTest {

    private fun result(title: String, series: String?) =
        SearchResult(
            frbrgroupid = null,
            title = title,
            author = null,
            versions =
                listOf(
                    BookVersion(
                        mmsid = title,
                        title = title,
                        author = null,
                        edition = null,
                        publisher = null,
                        publicationDate = null,
                        isbns = emptyList(),
                        frbrgroupid = null,
                        branches = emptyList(),
                        series = series
                    )
                )
        )

    @Test
    fun `orders by volume number regardless of volume notation`() {
        val sorted =
            sortBySeries(
                listOf(
                    result("Egzorcyzmy Dory Wilk", "Heksalogia o Dorze Wilk / Aneta Jadowska ; 5"),
                    result("Bogowie muszą być szaleni", "Heksalogia o Dorze Wilk ;  2"),
                    result("Wszystko zostaje w rodzinie", "Heksalogia o Dorze Wilk ; [t. 4]"),
                    result("Złodziej dusz", "Heksalogia o Dorze Wilk ; 10")
                )
            )

        assertEquals(
            listOf(
                "Bogowie muszą być szaleni",
                "Wszystko zostaje w rodzinie",
                "Egzorcyzmy Dory Wilk",
                "Złodziej dusz"
            ),
            sorted.map { it.title }
        )
    }

    @Test
    fun `groups by series name and puts books without series last`() {
        val sorted =
            sortBySeries(
                listOf(
                    result("Bez serii", null),
                    result("Martwy sezon", "Garstka z Ustki / Aneta Jadowska ; [t. 2]"),
                    result("Tom bez numeru", "Garstka z Ustki / Aneta Jadowska"),
                    result("Ciemno, prawie noc", "Garstka z Ustki ; [t. 1]"),
                    result("Kamień filozoficzny", "Harry Potter ; T.1")
                )
            )

        assertEquals(
            listOf(
                "Ciemno, prawie noc",
                "Martwy sezon",
                "Tom bez numeru",
                "Kamień filozoficzny",
                "Bez serii"
            ),
            sorted.map { it.title }
        )
    }
}
