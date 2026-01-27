package com.example.coupleapp.ui.components.locket

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupleapp.R

/**
 * Text content view for Locket with color picker
 */
@Composable
fun LocketTextContent(
    textContent: String,
    onTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    isSending: Boolean = false,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    
    var selectedColor by remember { mutableStateOf(Color(0xFF2D2D2D)) }
    
    // Use TextFieldValue to properly control cursor position
    var textFieldValue by remember(textContent) {
        mutableStateOf(TextFieldValue(
            text = textContent,
            selection = TextRange(textContent.length) // Always place cursor at end
        ))
    }
    
    // Sync external textContent changes with TextFieldValue
    LaunchedEffect(textContent) {
        if (textFieldValue.text != textContent) {
            textFieldValue = TextFieldValue(
                text = textContent,
                selection = TextRange(textContent.length)
            )
        }
    }
    
    val textColors = remember {
        listOf(
            Color(0xFF2D2D2D), // Black
            Color(0xFFFF6B6B), // Red
            Color(0xFFFF9ECE), // Pink
            Color(0xFFFFB347), // Orange
            Color(0xFF4CAF50), // Green
            Color(0xFF4D96FF), // Blue
            Color(0xFF845EC2), // Purple
            Color(0xFFFFFFFF), // White
        )
    }
    
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Text input box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE8F0FF),
                            Color(0xFFDEE8FF)
                        )
                    )
                )
                .clickable { focusRequester.requestFocus() }
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    if (newValue.text.length <= 200) {
                        textFieldValue = newValue
                        onTextChange(newValue.text)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = selectedColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 32.sp
                ),
                cursorBrush = SolidColor(Color(0xFF4CAF50)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                    }
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.type_message_love),
                                color = Color(0xFFB0B0B0),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                lineHeight = 28.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            
            // Character count
            if (textFieldValue.text.isNotEmpty()) {
                Text(
                    text = "${textFieldValue.text.length}/200",
                    color = Color(0xFF757575),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Color picker row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp)
        ) {
            items(textColors) { color ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selectedColor == color) 3.dp else 1.dp,
                            color = if (selectedColor == color) Color(0xFF4CAF50) else Color(0xFFE0E0E0),
                            shape = CircleShape
                        )
                        .clickable { selectedColor = color }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Send button
        CaptureButton(
            onClick = {
                focusManager.clearFocus()
                if (textContent.isNotBlank()) {
                    onSendText()
                }
            },
            color = if (textContent.isNotBlank()) Color(0xFF4CAF50) else Color(0xFFE0E0E0),
            enabled = textContent.isNotBlank() && !isSending
        )
    }
}
