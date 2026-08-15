package com.theundefined.omnis.data.model

// Publiczny, samowystarczalny mock serwera Primo (https://github.com/theundefined/omnis-mock) —
// pozwala wypróbować apkę bez prawdziwych danych logowania. defaultTimeoutSeconds jest wyższy niż
// domyślne 30s, bo darmowa instancja na Render potrzebuje ~50s+ na "obudzenie się" po bezczynności.
val MOCK_TENANT =
    Tenant(
        name = "Nieoficjalna Biblioteka OMNIS (Demo)",
        baseUrl = "https://omnis-mock.onrender.com",
        institution = "MOCK",
        view = "MOCK:MOCK",
        isDemo = true,
        defaultTimeoutSeconds = 60L
    )

val KNOWN_TENANTS =
    listOf(
        Tenant(
            name = "Biblioteka Raczyńskich (Poznań)",
            baseUrl = "https://omnis-br.primo.exlibrisgroup.com",
            institution = "48OMNIS_BRP",
            view = "48OMNIS_BRP:BRACZ"
        ),
        Tenant(
            name = "Biblioteka Narodowa",
            baseUrl = "https://katalogi.bn.org.pl",
            institution = "48OMNIS_NLOP",
            view = "48OMNIS_NLOP:48OMNIS_NLOP"
        ),
        Tenant(
            name = "Biblioteka UAM (Poznań)",
            baseUrl = "https://katalog.amu.edu.pl",
            institution = "48OMNIS_AMU",
            view = "48OMNIS_AMU:AMU"
        ),
        Tenant(
            name = "Biblioteka Publiczna w Łukowie",
            baseUrl = "https://omnis-lukowski3.primo.exlibrisgroup.com",
            institution = "48OMNIS_LUK3",
            view = "48OMNIS_LUK3:LUK3_3"
        ),
        Tenant(
            name = "Dolnośląska Biblioteka Publiczna (Wrocław)",
            baseUrl = "https://omnis-dbp.primo.exlibrisgroup.com",
            institution = "48OMNIS_WBP",
            view = "48OMNIS_WBP:48OMNIS_WBP"
        ),
        Tenant(
            name = "Uniwersytet Jagielloński (Kraków)",
            baseUrl = "https://katalogi.uj.edu.pl",
            institution = "48OMNIS_UJA",
            view = "48OMNIS_UJA:uja"
        ),
        Tenant(
            name = "Uniwersytet Mikołaja Kopernika (Toruń)",
            baseUrl = "https://szukaj.bu.umk.pl",
            institution = "48OMNIS_UMKWT",
            view = "48OMNIS_UMKWT:UMK"
        ),
        Tenant(
            name = "Wojewódzka Biblioteka Publiczna (Kielce)",
            baseUrl = "https://omnis-swietokrzyskie2.primo.exlibrisgroup.com",
            institution = "48OMNIS_SW2",
            view = "48OMNIS_SW2:SW2_4"
        ),
        Tenant(
            name = "Koszalińska Biblioteka Publiczna",
            baseUrl = "https://omnis-kbp.primo.exlibrisgroup.com",
            institution = "48OMNIS_KBP",
            view = "48OMNIS_KBP:48KBP"
        ),
        Tenant(
            name = "Książnica Zamojska (Zamość)",
            baseUrl = "https://omnis-zamojski.primo.exlibrisgroup.com",
            institution = "48OMNIS_ZAM",
            view = "48OMNIS_ZAM:ZAM_1"
        ),
        MOCK_TENANT
    )
