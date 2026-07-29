package com.alarmcontrol.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.alarmcontrol.R
import com.alarmcontrol.ui.insights.InsightsRoute
import com.alarmcontrol.ui.profiles.ProfileEditorRoute
import com.alarmcontrol.ui.profiles.ProfilesRoute
import com.alarmcontrol.ui.profiles.ProfilesViewModel
import com.alarmcontrol.ui.rules.QuickRuleDraft
import com.alarmcontrol.ui.rules.RuleEditorRoute
import com.alarmcontrol.ui.rules.RulesRoute
import com.alarmcontrol.ui.rules.RulesViewModel
import com.alarmcontrol.ui.settings.SettingsDestination
import com.alarmcontrol.ui.settings.SettingsRoute
import com.alarmcontrol.ui.settings.SettingsViewModel

private enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    RULES("rules", R.string.nav_rules, R.drawable.ic_nav_rules),
    INSIGHTS("insights", R.string.nav_insights, R.drawable.ic_nav_insights),
    PROFILES("profiles", R.string.nav_profiles, R.drawable.ic_nav_profiles),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_nav_settings),
}

/** Single-Activity host (§2) with compact bottom navigation and an expanded-width rail. */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    var quickRuleDraft by
        rememberSaveable(stateSaver = QuickRuleDraftSaver) {
            mutableStateOf<QuickRuleDraft?>(null)
        }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute =
        TopDestination.entries
            .firstOrNull { destination ->
                currentDestination?.hierarchy?.any { it.route == destination.route } == true
            }?.route
    val currentLeafRoute = currentDestination?.route
    val isSettingsDetail =
        currentLeafRoute?.startsWith("settings/") == true &&
            currentLeafRoute != SettingsDestination.OVERVIEW.route
    val showNavigation =
        currentLeafRoute != RULE_EDITOR_ROUTE &&
            currentLeafRoute != PROFILE_EDITOR_ROUTE &&
            !isSettingsDetail

    val onNavigate: (String) -> Unit = navController::navigateTopLevel

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AppNavigationScaffold(
            useNavigationRail = maxWidth >= NAVIGATION_RAIL_BREAKPOINT,
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            showNavigation = showNavigation,
        ) { contentModifier ->
            AlarmControlNavHost(
                navController = navController,
                modifier = contentModifier,
                quickRuleDraft = quickRuleDraft,
                onQuickRuleConsumed = { quickRuleDraft = null },
                onOpenRulesDraft = { draft ->
                    quickRuleDraft = draft
                    navController.navigateTopLevel(TopDestination.RULES.route)
                },
            )
        }
    }
}

@Composable
private fun AlarmControlNavHost(
    navController: NavHostController,
    quickRuleDraft: QuickRuleDraft?,
    onQuickRuleConsumed: () -> Unit,
    onOpenRulesDraft: (QuickRuleDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopDestination.RULES.route,
        modifier = modifier,
    ) {
        rulesGraph(navController, quickRuleDraft, onQuickRuleConsumed)
        composable(TopDestination.INSIGHTS.route) {
            InsightsDestination(onOpenRulesDraft)
        }
        profilesGraph(navController)
        settingsGraph(navController)
    }
}

private fun NavGraphBuilder.rulesGraph(
    navController: NavHostController,
    quickRuleDraft: QuickRuleDraft?,
    onQuickRuleConsumed: () -> Unit,
) {
    navigation(
        route = TopDestination.RULES.route,
        startDestination = RULES_LIST_ROUTE,
    ) {
        composable(RULES_LIST_ROUTE) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(TopDestination.RULES.route) }
            val viewModel: RulesViewModel = hiltViewModel(parent)
            RulesRoute(
                viewModel = viewModel,
                quickRuleDraft = quickRuleDraft,
                onQuickRuleConsumed = onQuickRuleConsumed,
                onOpenSettings = {
                    navController.navigateTopLevel(TopDestination.SETTINGS.route)
                },
                onOpenEditor = {
                    navController.navigate(RULE_EDITOR_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(RULE_EDITOR_ROUTE) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(TopDestination.RULES.route) }
            RuleEditorRoute(
                viewModel = hiltViewModel(parent),
                onClose = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun InsightsDestination(onOpenRulesDraft: (QuickRuleDraft) -> Unit) {
    InsightsRoute(
        onCreateRule = { packageName, category ->
            onOpenRulesDraft(QuickRuleDraft(packageName, category))
        },
        onCreateKeepRule = { packageName, channelId ->
            onOpenRulesDraft(
                QuickRuleDraft(
                    packageName = packageName,
                    category = null,
                    channelId = channelId,
                    keep = true,
                ),
            )
        },
        onCreateMarketingMonitor = { packageName ->
            onOpenRulesDraft(
                QuickRuleDraft(
                    packageName = packageName,
                    category = null,
                    marketingMonitor = true,
                ),
            )
        },
    )
}

private fun NavGraphBuilder.profilesGraph(navController: NavHostController) {
    navigation(
        route = TopDestination.PROFILES.route,
        startDestination = PROFILES_LIST_ROUTE,
    ) {
        composable(PROFILES_LIST_ROUTE) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(TopDestination.PROFILES.route) }
            val viewModel: ProfilesViewModel = hiltViewModel(parent)
            ProfilesRoute(
                viewModel = viewModel,
                onOpenEditor = {
                    navController.navigate(PROFILE_EDITOR_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(PROFILE_EDITOR_ROUTE) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(TopDestination.PROFILES.route) }
            ProfileEditorRoute(
                viewModel = hiltViewModel(parent),
                onClose = { navController.popBackStack() },
            )
        }
    }
}

private fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    navigation(
        route = TopDestination.SETTINGS.route,
        startDestination = SettingsDestination.OVERVIEW.route,
    ) {
        SettingsDestination.entries.forEach { destination ->
            composable(destination.route) { entry ->
                val parent =
                    remember(entry) {
                        navController.getBackStackEntry(TopDestination.SETTINGS.route)
                    }
                SettingsRoute(
                    viewModel = hiltViewModel<SettingsViewModel>(parent),
                    destination = destination,
                    onNavigate = {
                        navController.navigate(it.route) { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) { saveState = true }
    }
}

/** Stateless navigation chrome, split out so compact and expanded layouts are JVM-testable. */
@Composable
internal fun AppNavigationScaffold(
    useNavigationRail: Boolean,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    showNavigation: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    if (!showNavigation) {
        content(Modifier.fillMaxSize())
    } else if (useNavigationRail) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.testTag(NAVIGATION_RAIL_TEST_TAG)) {
                TopDestination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationRailItem(
                        selected = currentRoute == destination.route,
                        onClick = { onNavigate(destination.route) },
                        icon = { Icon(painterResource(destination.iconRes), contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
            content(Modifier.fillMaxSize().weight(1f))
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag(BOTTOM_NAVIGATION_TEST_TAG)) {
                    TopDestination.entries.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination.route) },
                            icon = { Icon(painterResource(destination.iconRes), contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            content(Modifier.fillMaxSize().padding(padding))
        }
    }
}

private val NAVIGATION_RAIL_BREAKPOINT = 600.dp
private const val RULES_LIST_ROUTE = "rules/list"
private const val RULE_EDITOR_ROUTE = "rules/editor"
private const val PROFILES_LIST_ROUTE = "profiles/list"
private const val PROFILE_EDITOR_ROUTE = "profiles/editor"
internal const val BOTTOM_NAVIGATION_TEST_TAG = "bottom_navigation"
internal const val NAVIGATION_RAIL_TEST_TAG = "navigation_rail"

internal val QuickRuleDraftSaver =
    listSaver<QuickRuleDraft?, Any>(
        save = { draft ->
            if (draft == null) {
                emptyList()
            } else {
                listOf(
                    draft.packageName,
                    draft.category != null,
                    draft.category.orEmpty(),
                    draft.channelId != null,
                    draft.channelId.orEmpty(),
                    draft.keep,
                    draft.marketingMonitor,
                )
            }
        },
        restore = { values ->
            if (values.isEmpty()) {
                null
            } else {
                QuickRuleDraft(
                    packageName = values[PACKAGE_NAME_INDEX] as String,
                    category =
                        if (values[CATEGORY_PRESENT_INDEX] as Boolean) {
                            values[CATEGORY_INDEX] as String
                        } else {
                            null
                        },
                    channelId =
                        if (values[CHANNEL_PRESENT_INDEX] as Boolean) {
                            values[CHANNEL_INDEX] as String
                        } else {
                            null
                        },
                    keep = values[KEEP_INDEX] as Boolean,
                    marketingMonitor = values[MARKETING_MONITOR_INDEX] as Boolean,
                )
            }
        },
    )

private const val PACKAGE_NAME_INDEX = 0
private const val CATEGORY_PRESENT_INDEX = 1
private const val CATEGORY_INDEX = 2
private const val CHANNEL_PRESENT_INDEX = 3
private const val CHANNEL_INDEX = 4
private const val KEEP_INDEX = 5
private const val MARKETING_MONITOR_INDEX = 6
