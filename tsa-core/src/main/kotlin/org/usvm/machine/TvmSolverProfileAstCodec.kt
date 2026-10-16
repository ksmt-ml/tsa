package org.usvm.machine

import com.jetbrains.rd.framework.UnsafeBuffer
import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.runner.serializer.AstDeserializer
import io.ksmt.runner.serializer.AstSerializationCtx
import io.ksmt.runner.serializer.AstSerializer
import java.util.Base64

internal data class TvmEncodedAst(
    val streamOrdinal: Long,
    val data: String,
)

/**
 * A versioned stream codec backed by KSMT's complete runner AST serializer.
 *
 * Entries in one profile share a serialization context and must be decoded in stream order. This
 * preserves DAG sharing and declarations without relying on debug text parsing.
 */
internal class TvmSolverProfileAstEncoder {
    private val serializationContext = AstSerializationCtx()
    private var expressionContext: KContext? = null
    private var nextOrdinal = 0L

    fun encode(expression: KExpr<*>): TvmEncodedAst {
        val currentContext = expressionContext
        if (currentContext == null) {
            expressionContext = expression.ctx
            serializationContext.initCtx(expression.ctx)
        } else {
            require(currentContext === expression.ctx) { "A replay stream cannot mix KSMT contexts" }
        }

        val bytes =
            UnsafeBuffer(ByteArray(INITIAL_BUFFER_SIZE)).use { buffer ->
                AstSerializer(serializationContext, buffer).serializeAst(expression)
                buffer.getArray().copyOf(buffer.position)
            }

        return TvmEncodedAst(
            streamOrdinal = nextOrdinal++,
            data = Base64.getEncoder().encodeToString(bytes),
        )
    }

    companion object {
        const val FORMAT_VERSION = "ksmt-runner-ast-stream-v0"
        private const val INITIAL_BUFFER_SIZE = 4096
    }
}

internal class TvmSolverProfileAstDecoder(
    context: KContext,
) {
    private val serializationContext = AstSerializationCtx().apply { initCtx(context) }
    private var nextOrdinal = 0L

    fun decode(encoded: TvmEncodedAst): KExpr<*> {
        require(encoded.streamOrdinal == nextOrdinal) {
            "Expected AST stream entry $nextOrdinal, got ${encoded.streamOrdinal}"
        }
        nextOrdinal++

        val bytes = Base64.getDecoder().decode(encoded.data)
        return UnsafeBuffer(bytes).use { buffer ->
            AstDeserializer(serializationContext, buffer).deserializeAst() as KExpr<*>
        }
    }
}
