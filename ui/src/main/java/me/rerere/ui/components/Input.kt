package me.rerere.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun <T : Number> OutlinedNumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var textFieldValue by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        modifier = modifier,
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            if (textFieldValue.isValidNumberInput()) {
                val newVal = try {
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is Int -> newValue.toInt() as T
                        is Float -> newValue.toFloat() as T
                        is Double -> newValue.toDouble() as T
                        else -> throw IllegalArgumentException("Unsupported number type")
                    }
                } catch (_: NumberFormatException) {
                    // Overflow or an unfinished number is expected while typing.
                    null
                }
                newVal?.let(onValueChange)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !textFieldValue.isValidNumberInput(),
        colors = colors
    )
}

private val NumberRegex = Regex("^[+-]?\\d+(\\.\\d+)?$")
private fun String.isValidNumberInput() = this.isNotEmpty() && NumberRegex.matches(this)
