package com.theundefined.omnis.ui.components

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.mapsSearchUrl
import com.theundefined.omnis.ui.BranchMapState
import com.theundefined.omnis.ui.MapBranch
import com.theundefined.omnis.ui.MapPin
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.PinStatus
import com.theundefined.omnis.ui.status
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** Mapa filii nad ekranem wyszukiwania — stan trzyma OmnisViewModel.branchMap. */
@Composable
fun BranchMapDialogHost(viewModel: OmnisViewModel) {
    val state by viewModel.branchMap.collectAsStateWithLifecycle()
    state?.let { BranchMapDialog(it, onDismiss = { viewModel.dismissBranchMap() }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchMapDialog(state: BranchMapState, onDismiss: () -> Unit) {
    val allPins = state.pins
    var onlyAvailable by remember { mutableStateOf(false) }
    val pins = if (onlyAvailable) allPins.filter { it.status() == PinStatus.AVAILABLE } else allPins
    // Filtr ma sens tylko, gdy coś odfiltruje i coś zostawi.
    val canFilter =
        allPins.any { it.status() == PinStatus.AVAILABLE } &&
            allPins.any { it.status() != PinStatus.AVAILABLE }
    var selected by remember { mutableStateOf<MapPin?>(null) }
    // Pinezka mogła się "rozrosnąć" (dołączyła kolejna filia z tego samego budynku) albo zniknąć
    // przez filtr — bierzemy jej aktualną wersję po położeniu.
    val selectedPin = selected?.let { s -> pins.firstOrNull { it.lat == s.lat && it.lon == s.lon } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1) },
                    navigationIcon = {
                        val backDescription = stringResource(R.string.cd_back)
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.semantics { contentDescription = backDescription }
                        ) {
                            Text("←", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                BranchMapView(
                    pins = pins,
                    selected = selectedPin,
                    fitToPins = state.isResolving || selectedPin == null,
                    onPinClick = { selected = it },
                    onMapClick = { selected = null },
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (canFilter) {
                            FilterChip(
                                selected = onlyAvailable,
                                onClick = { onlyAvailable = !onlyAvailable },
                                label = { Text(stringResource(R.string.map_only_available)) },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (allPins.isNotEmpty()) PinLegend()
                    }
                    BranchMapStatus(state)
                }

                selectedPin?.let { pin ->
                    PinDetailsCard(
                        pin = pin,
                        onClose = { selected = null },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchMapStatus(state: BranchMapState, modifier: Modifier = Modifier) {
    val total = state.branches.map { it.location.key }.distinct().size
    val done = state.coordinates.size
    val unresolved = state.unresolvedBranches
    val text =
        when {
            state.isResolving -> stringResource(R.string.branch_map_resolving, done, total)
            state.pins.isEmpty() -> stringResource(R.string.branch_map_empty)
            unresolved.isNotEmpty() ->
                stringResource(R.string.branch_map_unresolved, unresolved.joinToString { it.name })
            else -> return
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PinLegend() {
    val colors = PinColors.current()
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf(
                    PinStatus.AVAILABLE to R.string.map_legend_available,
                    PinStatus.BORROWED to R.string.map_legend_borrowed,
                    PinStatus.OVERDUE to R.string.map_legend_overdue,
                    PinStatus.UNKNOWN to R.string.map_legend_unknown
                )
                .forEach { (status, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier.size(10.dp)
                                    .background(colors.forStatus(status), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                    }
                }
        }
    }
}

@Composable
private fun PinDetailsCard(pin: MapPin, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp).heightIn(max = 360.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            pin.branches.forEachIndexed { index, branch ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                Text(branch.name, style = MaterialTheme.typography.titleSmall)
                branch.address?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                branch.holdings.forEach { holding ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            listOfNotNull(
                                    editionLabel(context, holding.version).takeIf { it != "-" },
                                    holding.version.publicationDate
                                )
                                .joinToString(", ")
                                .ifEmpty { "-" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        BranchStatusBadge(holding.branch)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                val first = pin.branches.first()
                TextButton(onClick = { openUrl(context, navigationUrl(first, pin)) }) {
                    Text(stringResource(R.string.branch_navigate))
                }
            }
        }
    }
}

// Link z Almy prowadzi do wizytówki miejsca (z nazwą, godzinami itp.), więc ma pierwszeństwo przed
// gołymi współrzędnymi.
private fun navigationUrl(branch: MapBranch, pin: MapPin): String =
    branch.location.mapsUrl ?: mapsSearchUrl("${pin.lat},${pin.lon}")

@Composable
private fun BranchMapView(
    pins: List<MapPin>,
    selected: MapPin?,
    fitToPins: Boolean,
    onPinClick: (MapPin) -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pinColors = PinColors.current()
    val currentOnPinClick by rememberUpdatedState(onPinClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)

    val mapView = remember {
        createMapView(context).apply {
            // Na spodzie stosu nakładek: tapnięcie w pinezkę obsłuży najpierw Marker, tu trafia
            // tylko tapnięcie w pustą mapę.
            overlays.add(
                0,
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            currentOnMapClick()
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }
                )
            )
        }
    }
    val markers = remember { mutableListOf<Marker>() }
    mapView.mapOverlay.setColorFilter(if (darkTheme) DARK_TILES else null)
    mapView.overlays.filterIsInstance<CopyrightOverlay>().forEach {
        it.setTextColor(
            if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        )
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(pins, pinColors, selected) {
        mapView.overlays.removeAll(markers)
        markers.clear()
        // Zaznaczona pinezka na końcu — rysuje się nad sąsiednimi.
        pins
            .sortedBy { it == selected }
            .forEach { pin ->
                val marker = Marker(mapView)
                marker.position = GeoPoint(pin.lat, pin.lon)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.icon =
                    pinIcon(
                        context,
                        pinColors.forStatus(pin.status()),
                        if (pin == selected) 1.5f else 1f
                    )
                marker.setOnMarkerClickListener { _, _ ->
                    currentOnPinClick(pin)
                    true
                }
                markers.add(marker)
            }
        mapView.overlays.addAll(markers)
        mapView.invalidate()
    }

    // Osobno od odświeżania pinezek, żeby zaznaczenie nie przesuwało widoku z powrotem na całość.
    LaunchedEffect(pins) {
        if (fitToPins && pins.isNotEmpty()) {
            if (mapView.width > 0 && mapView.height > 0) fitMap(mapView, pins)
            else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fitMap(mapView, pins) }
        }
    }

    // Zaznaczona pinezka na środek — nad kartą ze szczegółami, która zajmuje dół ekranu.
    LaunchedEffect(selected?.lat, selected?.lon) {
        selected?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lon)) }
    }
}

private fun pinIcon(context: Context, color: Color, scale: Float): Drawable {
    val vector = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)!!.mutate()
    vector.setTint(color.toArgb())
    val width = (vector.intrinsicWidth * scale).toInt()
    val height = (vector.intrinsicHeight * scale).toInt()
    return BitmapDrawable(context.resources, vector.toBitmap(width, height))
}

private fun createMapView(context: Context): MapView {
    // Serwery kafelków OSM blokują domyślny User-Agent osmdroida (mapa zostaje wtedy szara, bez
    // żadnego błędu); cache kafelków w cacheDir — bez uprawnień do pamięci.
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = context.cacheDir.resolve("osmdroid")
        osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        setMinZoomLevel(4.0)
        // Środek Polski, zanim dojdą pierwsze pinezki.
        controller.setZoom(6.0)
        controller.setCenter(GeoPoint(52.0, 19.4))
        // Wymóg licencji ODbL danych OpenStreetMap.
        overlays.add(CopyrightOverlay(context))
    }
}

private fun fitMap(mapView: MapView, pins: List<MapPin>) {
    if (pins.size == 1) {
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(GeoPoint(pins[0].lat, pins[0].lon))
    } else {
        val box = BoundingBox.fromGeoPoints(pins.map { GeoPoint(it.lat, it.lon) })
        val border = (64 * mapView.resources.displayMetrics.density).toInt()
        mapView.zoomToBoundingBox(box, false, border)
    }
}

// Negatyw kafelków z obrotem barwy o 180° — tło robi się ciemne, a woda zostaje niebieska, a
// zieleń zielona (sam negatyw z TilesOverlay.INVERT_COLORS dawałby pomarańczową wodę).
private val DARK_TILES =
    ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.574f,
                -1.430f,
                -0.144f,
                0f,
                255f,
                -0.426f,
                -0.430f,
                -0.144f,
                0f,
                255f,
                -0.426f,
                -1.430f,
                0.856f,
                0f,
                255f,
                0f,
                0f,
                0f,
                1f,
                0f
            )
        )
    )

private data class PinColors(
    val available: Color,
    val borrowed: Color,
    val overdue: Color,
    val unknown: Color
) {
    fun forStatus(status: PinStatus): Color =
        when (status) {
            PinStatus.AVAILABLE -> available
            PinStatus.BORROWED -> borrowed
            PinStatus.OVERDUE -> overdue
            PinStatus.UNKNOWN -> unknown
        }

    companion object {
        // Te same barwy co BranchStatusBadge na karcie wyniku.
        @Composable
        fun current() =
            PinColors(
                available = Color(0xFF388E3C),
                borrowed = Color(0xFFFBC02D),
                overdue = Color(0xFFD32F2F),
                unknown = MaterialTheme.colorScheme.outline
            )
    }
}
