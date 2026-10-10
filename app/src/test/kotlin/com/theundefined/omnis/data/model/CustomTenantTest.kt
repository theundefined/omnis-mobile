package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTenantTest {

    // Kształt jak w prawdziwej odpowiedzi /primaws/rest/pub/configuration/vid/... (przycięty).
    private fun configJson(name: String, code: String, vararg profiles: Pair<String, String>) =
        """
        {
          "primo-view": {"institution": {"description": "$name", "institution-code": "$code"}},
          "authentication": [${profiles.joinToString(",") {
            """{"profile-name": "${it.first}", "authentication-system": "${it.second}"}"""
        }}],
          "IsViewNdeEnabled": false
        }
        """
            .trimIndent()

    @Test
    fun `parses search link of an existing library`() {
        val link = parseCatalogLink(EXAMPLE_CATALOG_LINK)!!
        assertEquals("https://omnis-br.primo.exlibrisgroup.com", link.baseUrl)
        assertEquals("48OMNIS_BRP", link.institution)
        assertEquals("48OMNIS_BRP:BRACZ", link.view)
        assertFalse(link.isNde)
    }

    @Test
    fun `parses record link with other params and encoded colon`() {
        val link =
            parseCatalogLink(
                "  https://katalogi.uj.edu.pl/discovery/fulldisplay?docid=alma991&context=L" +
                    "&vid=48OMNIS_UJA%3Auja&lang=pl  "
            )!!
        assertEquals("https://katalogi.uj.edu.pl", link.baseUrl)
        assertEquals("48OMNIS_UJA", link.institution)
        assertEquals("48OMNIS_UJA:uja", link.view)
    }

    @Test
    fun `adds https when scheme is missing`() {
        val link = parseCatalogLink("katalog.amu.edu.pl/discovery/search?vid=48OMNIS_AMU:AMU")!!
        assertEquals("https://katalog.amu.edu.pl", link.baseUrl)
    }

    @Test
    fun `flags NDE links`() {
        assertTrue(
            parseCatalogLink(
                    "https://bn-rpl.primo.exlibrisgroup.com/nde/home?vid=48OMNIS_RPL:RPL"
                )!!
                .isNde
        )
    }

    @Test
    fun `rejects links without a usable vid or over plain http`() {
        assertNull(parseCatalogLink(""))
        assertNull(parseCatalogLink("https://omnis-br.primo.exlibrisgroup.com/discovery/search"))
        assertNull(parseCatalogLink("https://omnis-br.primo.exlibrisgroup.com/?vid=BRACZ"))
        assertNull(parseCatalogLink("https://example.com/?vid=A:B/../../x"))
        assertNull(
            parseCatalogLink("http://omnis-br.primo.exlibrisgroup.com/?vid=48OMNIS_BRP:BRACZ")
        )
    }

    @Test
    fun `known library resolves to its list entry`() {
        val link = parseCatalogLink(EXAMPLE_CATALOG_LINK)!!
        val config =
            parsePrimoViewConfig(
                configJson("Biblioteka Raczyńskich", "48OMNIS_BRP", "Alma" to "ALMA")
            )
        val known = KNOWN_TENANTS.first { it.view == "48OMNIS_BRP:BRACZ" }
        assertSame(known, resolveCustomTenant(link, config).getOrThrow())
    }

    @Test
    fun `unknown library with password login becomes a new tenant`() {
        val link =
            parseCatalogLink(
                "https://ucl.primo.exlibrisgroup.com/discovery/search?vid=44UCL_INST:UCL_VU2"
            )!!
        val config =
            parsePrimoViewConfig(
                configJson(
                    "University College London",
                    "44UCL_INST",
                    "SAML_AZURE" to "SAML",
                    "Alma" to "ALMA"
                )
            )
        val tenant = resolveCustomTenant(link, config).getOrThrow()
        assertEquals(
            Tenant(
                name = "University College London",
                baseUrl = "https://ucl.primo.exlibrisgroup.com",
                institution = "44UCL_INST",
                view = "44UCL_INST:UCL_VU2"
            ),
            tenant
        )
    }

    @Test
    fun `keeps a non-default ALMA profile name`() {
        val link = parseCatalogLink("https://x.primo.exlibrisgroup.com/?vid=INST:VIEW")!!
        val config = parsePrimoViewConfig(configJson("X", "INST", "Local_Users" to "ALMA"))
        assertEquals("Local_Users", resolveCustomTenant(link, config).getOrThrow().authProfile)
    }

    @Test
    fun `SSO-only library is rejected`() {
        val link = parseCatalogLink("https://x.primo.exlibrisgroup.com/?vid=INST:VIEW")!!
        val config = parsePrimoViewConfig(configJson("X", "INST", "SAML" to "SAML"))
        assertTrue(resolveCustomTenant(link, config).exceptionOrNull() is CustomTenantError.SsoOnly)
    }

    @Test
    fun `missing configuration means not a Primo VE catalog`() {
        val link = parseCatalogLink("https://example.com/nde/home?vid=INST:VIEW")!!
        val error = resolveCustomTenant(link, parsePrimoViewConfig("<html>")).exceptionOrNull()
        assertTrue(error is CustomTenantError.NotPrimo)
        assertTrue(error!!.message!!.contains("NDE"))
        assertNull(parsePrimoViewConfig("""{"foo": 1}"""))
    }
}
