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
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RecipesPanel(state: StudioState, onRunStarted: () -> Unit, modifier: Modifier = Modifier) {
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

/** Recipes that show the agent side of KMPwright; the rest teach the Playwright-style API. */
private val agentRecipeIds = setOf("agent", "read", "audit")

/** Expressive shapes and tonal colors rotate so neighbouring cards never look alike. */
private val recipeShapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny,
    MaterialShapes.SoftBurst, MaterialShapes.Cookie6Sided, MaterialShapes.Flower, MaterialShapes.Puffy,
)

private enum class RecipeTone { Primary, Secondary, Tertiary }

@Composable
private fun recipeToneColors(tone: RecipeTone) = when (tone) {
    RecipeTone.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    RecipeTone.Secondary -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    RecipeTone.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun RecipeGrid(onOpen: (Recipe) -> Unit, modifier: Modifier = Modifier) {
    val hero = recipes.first { it.id == "agent" }
    val forAgents = recipes.filter { it.id in agentRecipeIds && it != hero }
    val basics = recipes.filter { it.id !in agentRecipeIds }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier,
        contentPadding = panelPadding(bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                Text("Recipes", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    "Runnable scripts that drive the page above. Read the code, press run, watch it happen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = hero.id) { HeroRecipeCard(hero) { onOpen(hero) } }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("For agents") }
        itemsIndexed(forAgents, key = { _, recipe -> recipe.id }) { index, recipe ->
            RecipeCard(recipe, recipeShapes[(index + 1) % recipeShapes.size], RecipeTone.entries[(index + 1) % 3]) { onOpen(recipe) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Playwright basics") }
        itemsIndexed(basics, key = { _, recipe -> recipe.id }) { index, recipe ->
            RecipeCard(recipe, recipeShapes[(index + 3) % recipeShapes.size], RecipeTone.entries[index % 3]) { onOpen(recipe) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StepCount(count: Int, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            if (count == 1) "1 step" else "$count steps",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The headline recipe: full width, bold color, and the agent loop spelled out. */
@Composable
private fun HeroRecipeCard(recipe: Recipe, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = MaterialTheme.colorScheme.onPrimary,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(recipe.icon, contentDescription = null, modifier = Modifier.size(34.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(recipe.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "snapshot -i  →  click @e3  →  snapshot",
                    modifier = Modifier.padding(top = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, shape: androidx.graphics.shapes.RoundedPolygon, tone: RecipeTone, onClick: () -> Unit) {
    val (container, onContainer) = recipeToneColors(tone)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = shape.toShape(),
                    color = container,
                    contentColor = onContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(recipe.icon, contentDescription = null) }
                }
                Spacer(Modifier.weight(1f))
                StepCount(recipe.steps.size, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(recipe.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                recipe.summary,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class StepState { Pending, Running, Done, Failed, Cancelled }

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
            var cancelled = false
            try {
                state.withPage { page ->
                    try {
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
                    } finally {
                        withContext(NonCancellable) {
                            check(withTimeoutOrNull(5_000) { recipe.cleanup(page); true } == true) {
                                "Recipe cleanup timed out"
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                cancelled = true
                runs.filter { it.state == StepState.Running }.forEach { it.state = StepState.Cancelled; it.output = "Cancelled" }
                throw error
            } catch (error: Throwable) {
                runs.lastOrNull()?.let { it.state = StepState.Failed; it.output = error.message ?: error.toString() }
            } finally {
                running = false
                val failed = runs.any { it.state == StepState.Failed }
                val completed = !cancelled && runs.isNotEmpty() && runs.all { it.state == StepState.Done }
                state.showActivity(Activity(when {
                    completed -> "${recipe.title} finished"
                    failed -> "${recipe.title} failed — open the sheet for details"
                    else -> "${recipe.title} cancelled"
                }, done = completed))
                state.releasePageKeyboard()
                state.refreshRefs()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(panelPadding(bottom = 24.dp))) {
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
                                StepState.Cancelled -> Icon(Icons.Default.Close, contentDescription = "Cancelled", modifier = Modifier.size(16.dp))
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
