package app.revanced.patches.sony.soundconnect.speaktochat

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val GUARD = "Lapp/revanced/extension/sony/soundconnect/SpeakToChatGuard;"

// Sony's protocol classes keep their names; only the app's own classes are obfuscated.
private const val V1_PARAMETER_TYPE =
    "Lcom/sony/songpal/tandemfamily/message/mdr/v1/table1/param/smarttalkingmode/SmartTalkingModeParameterType;"
private const val V1_SETTING_VALUE =
    "Lcom/sony/songpal/tandemfamily/message/mdr/v1/table1/param/smarttalkingmode/SmartTalkingModeSettingValue;"
private const val MODE_VALUE =
    "Lcom/sony/songpal/mdr/j2objc/tandem/features/smarttalkingmode/SmartTalkingModeValue;"

private fun Instruction.isModeOnOffField() =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.let {
        it.definingClass == V1_PARAMETER_TYPE && it.name == "MODE_ON_OFF"
    } == true

/** `SmartTalkingModeValue.fromTableSet1(SmartTalkingModeSettingValue)`: wire value to app value. */
private fun Instruction.isModeValueConversion() =
    opcode == Opcode.INVOKE_STATIC &&
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == MODE_VALUE && it.name == "fromTableSet1" && it.parameterTypes.singleOrNull() == V1_SETTING_VALUE
        } == true

private fun Method.hasInstruction(predicate: Instruction.() -> Boolean) =
    implementation?.instructions?.any(predicate) == true

/**
 * Calls [hook] with the app-side Speak-to-Chat value right after the first conversion found
 * at or after [fromIndex], where the converted value sits in the `move-result-object` register.
 */
private fun MutableMethod.hookConvertedValue(fromIndex: Int, hook: String) {
    val list = instructions
    val conversionIndex = (fromIndex until list.size).firstOrNull { list[it].isModeValueConversion() }
        ?: throw PatchException("No Speak-to-Chat value conversion in $definingClass->$name")
    val moveResult = list[conversionIndex + 1]
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("Unexpected instruction after the conversion in $definingClass->$name")
    }
    val register = (moveResult as OneRegisterInstruction).registerA
    addInstruction(conversionIndex + 2, "invoke-static { v$register }, $GUARD->$hook(Ljava/lang/Object;)V")
}

@Suppress("unused")
val blockSpeakToChatGesturePatch = bytecodePatch(
    name = "Block Speak-to-Chat gesture",
    description = "Turns Speak-to-Chat back off whenever the headset switches it on by itself, " +
        "such as the two-finger hold on the WH-1000XM4 touch panel. " +
        "The other touch controls and the in-app Speak-to-Chat switch keep working.",
) {
    compatibleWith("com.sony.songpal.mdr"("13.3.0"))

    extendWith("extensions/speaktochat.rve")

    apply {
        // The command sender: its on/off method logs this string and sends the v1 MODE_ON_OFF parameter.
        // The v2 sender logs the same prefix but uses other protocol classes.
        val setter = firstMethod("sendSmartTalkingModeValue : onOff = ") {
            parameterTypes.map(CharSequence::toString) == listOf("Z", "Z", "Ljava/lang/String;") &&
                hasInstruction { isModeOnOffField() }
        }

        setter.addInstruction(0, "invoke-static { p0, p1 }, $GUARD->onAppRequest(Ljava/lang/Object;Z)V")

        val senderConstructor = firstMethod {
            definingClass == setter.definingClass && name == "<init>"
        }
        val superCallIndex = senderConstructor.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
        if (superCallIndex < 0) throw PatchException("No super call in the sender's constructor")
        senderConstructor.addInstruction(
            superCallIndex + 1,
            "invoke-static { p0 }, $GUARD->registerSender(Ljava/lang/Object;)V",
        )

        // Let the extension call the setter by its obfuscated name.
        firstMethod { definingClass == GUARD && name == "setterName" }
            .replaceInstruction(0, "const-string v0, \"${setter.name}\"")

        // The state holder: it receives the headset's notifications.
        val notificationHandler = firstMethod("invalid type !! must be SmartTalkingModeSetNtfyParam")
        val modeOnOffIndex = notificationHandler.instructions.indexOfFirst { it.isModeOnOffField() }
        if (modeOnOffIndex < 0) throw PatchException("No MODE_ON_OFF branch in the notification handler")
        notificationHandler.hookConvertedValue(modeOnOffIndex, "onNotifiedState")

        // The same class reads the full state once the headset is connected.
        val initialStateReader = firstMethod {
            definingClass == notificationHandler.definingClass &&
                name != notificationHandler.name &&
                parameterTypes.isEmpty() &&
                hasInstruction { isModeValueConversion() }
        }
        initialStateReader.hookConvertedValue(0, "onInitialState")
    }
}
