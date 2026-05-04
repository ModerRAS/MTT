package com.mtt.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Shapes
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Additional shape tokens for specific components
object ShapeTokens {
    // Button shapes
    val buttonShape = RoundedCornerShape(20.dp)
    val outlinedButtonShape = RoundedCornerShape(20.dp)
    val textButtonShape = RoundedCornerShape(4.dp)
    
    // Card shapes
    val cardShape = RoundedCornerShape(12.dp)
    val elevatedCardShape = RoundedCornerShape(12.dp)
    val outlinedCardShape = RoundedCornerShape(12.dp)
    
    // Dialog shapes
    val dialogShape = RoundedCornerShape(28.dp)
    
    // Bottom sheet shapes
    val bottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    
    // Snackbar shapes
    val snackbarShape = RoundedCornerShape(4.dp)
    
    // Chip shapes
    val chipShape = RoundedCornerShape(8.dp)
    val outlinedChipShape = RoundedCornerShape(8.dp)
    
    // TextField shapes
    val textFieldShape = RoundedCornerShape(4.dp, 4.dp, 0.dp, 0.dp)
    val outlinedTextFieldShape = RoundedCornerShape(4.dp)
    
    // Navigation bar shapes
    val navigationBarShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    
    // FAB shapes
    val fabShape = RoundedCornerShape(16.dp)
    val smallFabShape = RoundedCornerShape(12.dp)
    val extendedFabShape = RoundedCornerShape(16.dp)
}
