package com.epubmerger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.epubmerger.app.ui.AppViewModel
import com.epubmerger.app.ui.CompareScreen
import com.epubmerger.app.ui.ImportScreen
import com.epubmerger.app.ui.MergeScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    EpubMergerNavHost(viewModel)
                }
            }
        }
    }
}

private object Routes {
    const val IMPORT = "import"
    const val COMPARE = "compare"
    const val MERGE = "merge"
}

@Composable
fun EpubMergerNavHost(viewModel: AppViewModel) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.IMPORT) {
        composable(Routes.IMPORT) {
            ImportScreen(
                viewModel = viewModel,
                onCompare = { navController.navigate(Routes.COMPARE) }
            )
        }
        composable(Routes.COMPARE) {
            CompareScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onProceed = { navController.navigate(Routes.MERGE) }
            )
        }
        composable(Routes.MERGE) {
            MergeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
