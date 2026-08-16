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
        // Poniższe wpisy zostały zaimportowane z omnis-py/src/omnis/tenants.py (odkryte i
        // zweryfikowane tamtejszym scripts/discover_tenants.py) — kolejność zgodna z plikiem
        // źródłowym, żeby diff między projektami pozostał czytelny.
        Tenant(
            name = "Europejskie Centrum Solidarności (Gdańsk)",
            baseUrl = "https://katalog.ecs.gda.pl",
            institution = "48FAR_ECS",
            view = "48FAR_ECS:TEST_AF"
        ),
        Tenant(
            name = "Gdański Uniwersytet Medyczny",
            baseUrl = "https://katalog.gumed.edu.pl",
            institution = "48FAR_GUM",
            view = "48FAR_GUM:48GUM"
        ),
        Tenant(
            name = "Biblioteka Politechniki Gdańskiej",
            baseUrl = "https://katalogbpg.pg.edu.pl",
            institution = "48FAR_PGD",
            view = "48FAR_PGD:48PGD"
        ),
        Tenant(
            name = "Uniwersytet Gdański",
            baseUrl = "https://katalog-bug.ug.edu.pl",
            institution = "48FAR_UGD",
            view = "48FAR_UGD:48UGD"
        ),
        Tenant(
            name = "Akademia Górniczo-Hutnicza (Kraków)",
            baseUrl = "https://katalog.agh.edu.pl",
            institution = "48OMNIS_AGH",
            view = "48OMNIS_AGH:48AGH"
        ),
        Tenant(
            name = "Biblioteka Elbląska im. Cypriana Norwida",
            baseUrl = "https://omnis-be.primo.exlibrisgroup.com",
            institution = "48OMNIS_BE",
            view = "48OMNIS_BE:BE"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna – Centrum Wiedzy (Bolesławiec)",
            baseUrl = "https://omnis-mbpb.primo.exlibrisgroup.com",
            institution = "48OMNIS_BOL",
            view = "48OMNIS_BOL:23901"
        ),
        Tenant(
            name = "Biblioteka Publiczna m.st. Warszawy – Biblioteka Główna Woj. Mazowieckiego",
            baseUrl = "https://omnis-wbpw.primo.exlibrisgroup.com",
            institution = "48OMNIS_BPW",
            view = "48OMNIS_BPW:48OMNIS_BPW_BPWKoszykowa"
        ),
        Tenant(
            name = "Chełmska Biblioteka Publiczna (Chełm)",
            baseUrl = "https://omnis-chelmski.primo.exlibrisgroup.com",
            institution = "48OMNIS_CHE",
            view = "48OMNIS_CHE:CHE_1"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna im. Galla Anonima (Głogów)",
            baseUrl = "https://eu05.primo.exlibrisgroup.com",
            institution = "48OMNIS_GLO",
            view = "48OMNIS_GLO:48GLO"
        ),
        Tenant(
            name = "Biblioteki powiatu janowskiego (Janów Lubelski)",
            baseUrl = "https://omnis-janowski.primo.exlibrisgroup.com",
            institution = "48OMNIS_JAN",
            view = "48OMNIS_JAN:JAN"
        ),
        Tenant(
            name = "Książnica Pomorska im. Stanisława Staszica (Szczecin)",
            baseUrl = "https://omnis-kps.primo.exlibrisgroup.com",
            institution = "48OMNIS_KPS",
            view = "48OMNIS_KPS:48KPS"
        ),
        Tenant(
            name = "Biblioteki powiatu kraśnickiego (Kraśnik)",
            baseUrl = "https://omnis-krasnicki.primo.exlibrisgroup.com",
            institution = "48OMNIS_KR",
            view = "48OMNIS_KR:48OMNIS_KR_5"
        ),
        Tenant(
            name = "Biblioteki powiatów kraśnickiego i krasnostawskiego (Krasnystaw)",
            baseUrl = "https://omnis-krasnicki-krasnostawski.primo.exlibrisgroup.com",
            institution = "48OMNIS_KRA",
            view = "48OMNIS_KRA:KRA_4"
        ),
        Tenant(
            name = "Katolicki Uniwersytet Lubelski Jana Pawła II (Lublin)",
            baseUrl = "https://katalog.kul.pl",
            institution = "48OMNIS_KUL",
            view = "48OMNIS_KUL:KUL"
        ),
        Tenant(
            name = "Biblioteki powiatu kutnowskiego (Kutno)",
            baseUrl = "https://omnis-kutnowski.primo.exlibrisgroup.com",
            institution = "48OMNIS_KUT",
            view = "48OMNIS_KUT:KUT_2"
        ),
        Tenant(
            name = "Biblioteki powiatów lubartowskiego i ryckiego",
            baseUrl = "https://omnis-lubartowski-rycki.primo.exlibrisgroup.com",
            institution = "48OMNIS_LIR",
            view = "48OMNIS_LIR:LIR_2"
        ),
        Tenant(
            name =
                "Biblioteki powiatów brzezińskiego, łódzkiego wsch., opoczyńskiego, " +
                    "pajęczańskiego, piotrkowskiego i poddębickiego",
            baseUrl = "https://omnis-lodzkie1.primo.exlibrisgroup.com",
            institution = "48OMNIS_LO1",
            view = "48OMNIS_LO1:LO1_5"
        ),
        Tenant(
            name = "Powiatowa Biblioteka Publiczna (powiat łowicki)",
            baseUrl = "https://omnis-lowicki.primo.exlibrisgroup.com",
            institution = "48OMNIS_LOW",
            view = "48OMNIS_LOW:LOW_3"
        ),
        Tenant(
            name = "MBP im. T. Różewicza (Wrocław)",
            baseUrl = "https://omnis-mbpwr.primo.exlibrisgroup.com",
            institution = "48OMNIS_MBP",
            view = "48OMNIS_MBP:MBP"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna (Gdynia)",
            baseUrl = "https://omnis-mbpg.primo.exlibrisgroup.com",
            institution = "48OMNIS_MBPG",
            view = "48OMNIS_MBPG:48MBPG"
        ),
        Tenant(
            name = "Biblioteka Miejska w Łodzi",
            baseUrl = "https://katalog.biblioteka.lodz.pl",
            institution = "48OMNIS_MBPL",
            view = "48OMNIS_MBPL:48MBPL"
        ),
        Tenant(
            name = "Wojewódzka Biblioteka Publiczna im. H. Łopacińskiego (Lublin)",
            baseUrl = "https://bn-mpl.primo.exlibrisgroup.com",
            institution = "48OMNIS_MPL",
            view = "48OMNIS_MPL:48OMNIS_MPL"
        ),
        Tenant(
            name = "Powiatowa Biblioteka Publiczna (Opole Lubelskie)",
            baseUrl = "https://omnis-opolski.primo.exlibrisgroup.com",
            institution = "48OMNIS_OPO",
            view = "48OMNIS_OPO:OPO_1"
        ),
        Tenant(
            name = "Powiatowa Biblioteka Publiczna – Centrum Kultury (Parczew)",
            baseUrl = "https://omnis-parczewski.primo.exlibrisgroup.com",
            institution = "48OMNIS_PAR",
            view = "48OMNIS_PAR:PAR_4"
        ),
        Tenant(
            name = "Biblioteka Naukowa PAU i PAN (Kraków)",
            baseUrl = "https://omnis-pau.primo.exlibrisgroup.com",
            institution = "48OMNIS_PAU",
            view = "48OMNIS_PAU:48PAU"
        ),
        Tenant(
            name = "Biblioteka Główna UPJP2 (Kraków)",
            baseUrl = "https://omnis-upjp.primo.exlibrisgroup.com",
            institution = "48OMNIS_PUJP",
            view = "48OMNIS_PUJP:48PUJP"
        ),
        Tenant(
            name = "Biblioteki powiatu radzyńskiego (Radzyń Podlaski)",
            baseUrl = "https://omnis-radzynski.primo.exlibrisgroup.com",
            institution = "48OMNIS_RAD",
            view = "48OMNIS_RAD:RAD_4"
        ),
        // Celowo pominięte: Wojewódzka Biblioteka Publiczna im. W. Gombrowicza (Kielce, NDE) —
        // 48OMNIS_RPL, https://bn-rpl.primo.exlibrisgroup.com. Ta sama fizyczna biblioteka co
        // "Wojewódzka Biblioteka Publiczna (Kielce)" (48OMNIS_SW2) powyżej, ale pod
        // nowszym/innym kodem instytucji hostowanym przez BN, który udostępnia wyłącznie Primo
        // "New Discovery Experience" (/nde/home), a nie klasyczny przepływ /discovery/search +
        // /primaws/suprimaLogin, na którym opiera się logowanie w tej apce (patrz CLAUDE.md).
        // Bez zmiany logiki logowania to konto nigdy by się nie zalogowało, a w dropdownie
        // sąsiadowałoby z prawie identycznie nazwanym, działającym wpisem Kielc — myliłoby to
        // użytkowników, więc nie jest dodawane do KNOWN_TENANTS. Pełny wpis (do ew. przyszłej
        // implementacji NDE) jest w omnis-py/src/omnis/tenants.py.
        Tenant(
            name = "Miejska Biblioteka Publiczna (Skierniewice)",
            baseUrl = "https://omnis-skierniewicki.primo.exlibrisgroup.com",
            institution = "48OMNIS_SKI",
            view = "48OMNIS_SKI:SKI_1"
        ),
        Tenant(
            name = "Biblioteki powiatów buskiego, jędrzejowskiego, kieleckiego i koneckiego",
            baseUrl = "https://omnis-swietokrzyskie1.primo.exlibrisgroup.com",
            institution = "48OMNIS_SW1",
            view = "48OMNIS_SW1:SW1_7"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna im. T. Zamoyskiego (Tomaszów Lubelski)",
            baseUrl = "https://omnis-tomaszowski-lubelski.primo.exlibrisgroup.com",
            institution = "48OMNIS_TL",
            view = "48OMNIS_TL:48OMNIS_TL_4"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna (Tomaszów Mazowiecki)",
            baseUrl = "https://omnis-tomaszowski.primo.exlibrisgroup.com",
            institution = "48OMNIS_TOM",
            view = "48OMNIS_TOM:TOM_1"
        ),
        Tenant(
            name = "Politechnika Wrocławska",
            baseUrl = "https://omnis-pwr.primo.exlibrisgroup.com",
            institution = "48OMNIS_TUR",
            view = "48OMNIS_TUR:48TUR"
        ),
        Tenant(
            name = "Uniwersytet Kardynała Stefana Wyszyńskiego (Warszawa)",
            baseUrl = "https://omnis-uksw.primo.exlibrisgroup.com",
            institution = "48OMNIS_UKSW",
            view = "48OMNIS_UKSW:PRIMO"
        ),
        Tenant(
            name = "Biblioteka Główna UMCS (Lublin)",
            baseUrl = "https://omnis-umcs.primo.exlibrisgroup.com",
            institution = "48OMNIS_UMCS",
            view = "48OMNIS_UMCS:UMCS"
        ),
        Tenant(
            name = "Uniwersytet Opolski",
            baseUrl = "https://omnis-uo.primo.exlibrisgroup.com",
            institution = "48OMNIS_UOP",
            view = "48OMNIS_UOP:48UOP"
        ),
        Tenant(
            name = "Uniwersytet Warszawski",
            baseUrl = "https://omnis-buw.primo.exlibrisgroup.com",
            institution = "48OMNIS_UOW",
            view = "48OMNIS_UOW:48UOW"
        ),
        Tenant(
            name = "Uniwersytet Wrocławski",
            baseUrl = "https://katalog.uwr.edu.pl",
            institution = "48OMNIS_UWR",
            view = "48OMNIS_UWR:STG"
        ),
        Tenant(
            name = "Wojewódzka Biblioteka Publiczna im. Marszałka J. Piłsudskiego (Łódź)",
            baseUrl = "https://omnis-wbpl.primo.exlibrisgroup.com",
            institution = "48OMNIS_WBPL",
            view = "48OMNIS_WBPL:48OMNIS_WBPL"
        ),
        Tenant(
            name = "Wojewódzka Biblioteka Publiczna (Olsztyn)",
            baseUrl = "https://omnis-wbpo.primo.exlibrisgroup.com",
            institution = "48OMNIS_WBPO",
            view = "48OMNIS_WBPO:WBPO"
        ),
        Tenant(
            name = "Powiatowa Biblioteka Publiczna (Wieluń)",
            baseUrl = "https://omnis-wielunski.primo.exlibrisgroup.com",
            institution = "48OMNIS_WIE",
            view = "48OMNIS_WIE:WIE_1"
        ),
        Tenant(
            name = "Miejska Biblioteka Publiczna (Zduńska Wola)",
            baseUrl = "https://omnis-zdunskowolski.primo.exlibrisgroup.com",
            institution = "48OMNIS_ZDU",
            view = "48OMNIS_ZDU:ZDU_1"
        ),
        Tenant(
            name = "Miejsko-Powiatowa Biblioteka Publiczna (Zgierz)",
            baseUrl = "https://omnis-zgierski.primo.exlibrisgroup.com",
            institution = "48OMNIS_ZGI",
            view = "48OMNIS_ZGI:ZGI_1"
        ),
        Tenant(
            name = "Zakład Narodowy im. Ossolińskich (Wrocław)",
            baseUrl = "https://omnis-zno.primo.exlibrisgroup.com",
            institution = "48OMNIS_ZNO",
            view = "48OMNIS_ZNO:ZNO"
        ),
        Tenant(
            name = "Uniwersytet Warmińsko-Mazurski (Olsztyn)",
            baseUrl = "https://uwm.primo.exlibrisgroup.com",
            institution = "48UWM_INST",
            view = "48UWM_INST:48UWM"
        ),
        MOCK_TENANT
    )
