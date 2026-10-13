package com.bernaferrari.kmpwright.example

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Each recipe gets its own expressive shape so the grid reads at a glance. */
@Composable
private fun recipeShape(index: Int): Shape = when (index % 6) {
    0 -> MaterialShapes.Cookie9Sided
    1 -> MaterialShapes.Clover4Leaf
    2 -> MaterialShapes.Sunny
    3 -> MaterialShapes.SoftBurst
    4 -> MaterialShapes.Cookie6Sided
    else -> MaterialShapes.Pill
}.toShape()

@Composable
internal fun RecipesPanel(state: StudioState, onRunStarted: () -> Unit, modifier: Modifier = Modifier) {
    val native = LocalNativeNavigation.current
    if (native != null) {
        // The native navigation stack pushes RecipeDetailPanel as its own screen.
        RecipeGrid(onOpen = native.openRecipe, modifier = modifier)
        return
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = recipes.firstOrNull { it.id == openId }
    AnimatedContent(
        targetState = open,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "recipe",
    ) { recipe ->
        if (recipe == null) RecipeGrid(onOpen = { openId = it.id }, modifier = Modifier.fillMaxSize())
        else RecipeDetail(state, recipe, onBack = { openId = null }, onRunStarted = onRunStarted)
    }
}

/** A recipe as a standalone screen, for native navigation stacks. */
@Composable
internal fun RecipeDetailPanel(state: StudioState, recipe: Recipe, modifier: Modifier = Modifier) {
    Box(modifier) { RecipeDetail(state, recipe, onBack = null, onRunStarted = {}) }
}

@Composable
private fun RecipeGrid(onOpen: (Recipe) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)) {
                Text("Recipes", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    "Runnable scripts that drive the page above. Read the code, press run, watch it happen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(recipes, key = { it.id }) { recipe ->
            val index = recipes.indexOf(recipe)
            val hero = index == 0
            Surface(
                onClick = { onOpen(recipe) },
                shape = MaterialTheme.shapes.extraLarge,
                color = if (hero) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (hero) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().heightIn(min = 156.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = recipeShape(index),
                        color = if (hero) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (hero) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(recipe.icon, contentDescription = null) }
                    }
                    Text(recipe.title, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        recipe.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hero) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private enum class StepState { Pending, Running, Done, Failed }

private class StepRun(val label: String) {
    var state by mutableStateOf(StepState.Pending)
    var output by mutableStateOf<String?>(null)
    var image by mutableStateOf<ImageBitmap?>(null)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecipeDetail(state: StudioState, recipe: Recipe, onBack: (() -> Unit)?, onRunStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val runs = remember(recipe) { mutableStateListOf<StepRun>() }
    var running by remember(recipe) { mutableStateOf(false) }
    val finished = runs.isNotEmpty() && !running

    fun run() {
        if (running) return
        running = true
        runs.clear()
        runs.addAll(recipe.steps.map { StepRun(it.label) })
        state.clearRefs()
        onRunStarted()
        scope.launch {
            try {
                state.withPage { page ->
                    for ((index, step) in recipe.steps.withIndex()) {
                        val run = runs[index]
                        run.state = StepState.Running
                        state.activity = Activity("${recipe.title} · ${step.label}")
                        try {
                            val output = step.run(page)
                            run.output = output?.text
                            run.image = output?.image?.let { runCatching { it.bytes.decodeToImageBitmap() }.getOrNull() }
                            run.state = StepState.Done
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            run.output = error.message ?: error.toString()
                            run.state = StepState.Failed
                            break
                        }
                    }
                }
            } finally {
                running = false
                val failed = runs.any { it.state == StepState.Failed }
                val outcome = Activity(if (failed) "${recipe.title} failed — open the sheet for details" else "${recipe.title} finished", done = !failed)
                state.activity = outcome
                state.refreshRefs()
                delay(4_000)
                if (state.activity == outcome) state.activity = null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All recipes")
                }
                Text(recipe.title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
            }
        }
        Text(
            recipe.summary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CodeBlock(recipe.code)
        Button(
            onClick = ::run,
            enabled = !running,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        ) {
            when {
                running -> LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                finished -> Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
                else -> Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
            }
            Text(
                when {
                    running -> "  Running…"
                    finished -> "  Run again"
                    else -> "  Run recipe"
                },
                style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
            )
        }
        runs.forEachIndexed { index, step -> StepRow(index + 1, step, isLast = index == runs.lastIndex) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepRow(number: Int, step: StepRun, isLast: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateContentSize()) {
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                when (step.state) {
                    StepState.Running -> LoadingIndicator(Modifier.size(36.dp))
                    else -> Surface(
                        modifier = Modifier.size(30.dp),
                        shape = if (step.state == StepState.Pending) MaterialShapes.Circle.toShape() else MaterialShapes.Cookie4Sided.toShape(),
                        color = when (step.state) {
                            StepState.Done -> MaterialTheme.colorScheme.tertiary
                            StepState.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = when (step.state) {
                            StepState.Done -> MaterialTheme.colorScheme.onTertiary
                            StepState.Failed -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (step.state) {
                                StepState.Done -> Icon(Icons.Default.Check, contentDescription = "Done", modifier = Modifier.size(16.dp))
                                StepState.Failed -> Icon(Icons.Default.Close, contentDescription = "Failed", modifier = Modifier.size(16.dp))
                                else -> Text("$number", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (!isLast) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(
                            if (step.state == StepState.Done) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.extraSmall,
                        )
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(step.label, style = MaterialTheme.typography.titleSmall)
            step.output?.let { output ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (step.state == StepState.Failed) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (step.state == StepState.Failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        output,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            step.image?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "Captured screenshot",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(MaterialTheme.shapes.large),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
