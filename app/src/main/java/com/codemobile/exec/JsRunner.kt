package com.codemobile.exec

import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.io.File

/**
 * Ejecuta scripts JavaScript con Rhino (motor JS puro JVM, sin NDK). Más lento
 * que un motor nativo tipo QuickJS, pero no requiere compilación nativa y por
 * eso compila de forma confiable en CI sin configuración adicional.
 *
 * Usa ContextFactory.getGlobal().call(...) en vez de enter()/exit() manual:
 * ese patrón asegura que el Context quede correctamente asociado al hilo
 * actual durante TODA la evaluación (incluyendo las llamadas a console.log
 * desde dentro del script), evitando errores de "contexto no disponible"
 * que pueden darse si el Context se resuelve fuera de la pila de evaluación.
 *
 * Expone un `console.log` mínimo que acumula su salida en un buffer, ya que
 * Rhino no trae uno por defecto (no es un entorno Node/browser).
 *
 * Si se pasa [sourceFile], el código pasa primero por JsModuleLoader para
 * resolver sus imports/exports de ES modules (sintaxis que Rhino no
 * entiende nativamente) antes de evaluarlo — ver JsModuleLoader para el
 * detalle de esa resolución.
 */
object JsRunner {

    fun run(code: String, sourceFile: File? = null): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val output = StringBuilder()
        var errorMessage: String? = null
        val finalCode = if (sourceFile != null) JsModuleLoader.flatten(sourceFile, code) else code

        try {
            ContextFactory.getGlobal().call { cx ->
                cx.optimizationLevel = -1 // evita generación de bytecode, más compatible en Android
                val scope = cx.initStandardObjects()

                val console = NativeObject()
                val logFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(
                        innerCx: RhinoContext?,
                        innerScope: Scriptable?,
                        thisObj: Scriptable?,
                        args: Array<out Any?>?
                    ): Any {
                        val line = args?.joinToString(" ") { arg ->
                            when (arg) {
                                null -> "null"
                                is Scriptable -> RhinoContext.toString(arg)
                                else -> arg.toString()
                            }
                        } ?: ""
                        output.append(line).append("\n")
                        return Undefined.instance
                    }
                }
                ScriptableObject.putProperty(console, "log", logFn)
                ScriptableObject.putProperty(scope, "console", console)

                cx.evaluateString(scope, finalCode, "script.js", 1, null)
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: e.toString()
        }

        val duration = System.currentTimeMillis() - startTime
        return ExecutionResult(output.toString(), errorMessage, duration)
    }
}
