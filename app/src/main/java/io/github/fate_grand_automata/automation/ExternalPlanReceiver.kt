package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.decision.*
import io.github.fga.automation.ExternalPlanProtocol
import io.github.fga.automation.RpcFailure
import io.github.fga.automation.RunSnapshot
import io.github.fga.automation.RunState
import io.github.lib_automata.Pattern
import io.github.lib_automata.ScriptAbortException
import kotlinx.serialization.json.*

/** The same still-owned task accepted stop, before the runner cancellation flag arrives. */
internal fun acceptedOwnedSmartStop(run:RunSnapshot,owner:String?,smartRequest:Boolean,runnerPresent:Boolean):Boolean =
    run.runId==owner && run.decisionMode=="smart" && smartRequest && runnerPresent &&
        !run.quiescent && !run.reconciliationRequired && run.state==RunState.Stopping && run.pendingControl=="stop"

/** Receives explicit FGOAgent actions. No skill, target, NP or card policy lives here. */
class ExternalPlanReceiver(
    private val runId:String,private val protocol:ExternalPlanProtocol,private val frames:ExternalFrameStore,
    private val requireOwner:()->Unit,
    private val acceptedStop:()->Boolean={false},
) : NativeBattleDecisionProvider {
    override val enabled=true
    private var pending:String?=null
    private fun capture(capture:()->Pattern)=capture().use { frames.save(runId,it) }
    private fun nativeFailure(error:RpcFailure):Nothing {
        // RPC guards still reject stopped tasks. Only this native caller can
        // classify its own accepted stop as the ordinary script cancellation.
        if(error.code=="ExternalPlanNotPermitted" && acceptedStop()) throw ScriptAbortException()
        throw error
    }
    override fun await(context:NativeDecisionContext,capture:()->Pattern,checkCancelled:()->Unit):NativeDecisionCommand {
        try {
            requireOwner();checkCancelled()
            val ready=protocol.publish(runId,contextJson(context),capture(capture))
            while(true) {
                checkCancelled();requireOwner()
                val receipt=protocol.accepted(runId)
                if(receipt!=null) {
                    check(receipt.observationId==ready.observationId) { "Accepted plan belongs to another native observation" }
                    pending=receipt.planId
                    return NativeDecisionCommand(receipt.planId,action(receipt.action))
                }
                Thread.sleep(50)
            }
        } catch(error:RpcFailure) {
            nativeFailure(error)
        }
    }
    override fun executing(command:NativeDecisionCommand) {
        try {
            requireOwner();check(command.planId==pending)
            protocol.executing(runId,command.planId)
        } catch(error:RpcFailure) {
            nativeFailure(error)
        }
    }
    override fun completed(command:NativeDecisionCommand) { protocol.completed(runId,command.planId);pending=null }
    override fun failed(command:NativeDecisionCommand,error:String) { protocol.failed(runId,command.planId,error);pending=null }
    override fun finishedTurn(context:NativeDecisionContext) { protocol.transition(runId) }
    companion object {
        fun contextJson(context:NativeDecisionContext)=buildJsonObject {
            put("battle",context.battle);put("turn",context.turn);put("wave",context.wave);put("waveTurn",context.waveTurn)
            put("phase",if(context.phase==NativeDecisionPhase.Skills) "skills" else "cards")
            putJsonArray("cards") {
                context.cards.forEach { card -> add(buildJsonObject {
                    put("index",card.index);put("type",card.type);put("affinity",card.affinity);put("servant",card.servant)
                    put("fieldSlot",card.fieldSlot?.let(::JsonPrimitive) ?: JsonNull);put("stunned",card.stunned)
                }) }
            }
        }
        fun action(value:JsonObject):NativeDecisionAction {
            fun n(key:String)=value.getValue(key).jsonPrimitive.int
            fun target()=value["target"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.int
            return when(value.getValue("type").jsonPrimitive.content) {
                "servant_skill" -> NativeDecisionAction.ServantSkill(n("slot"),n("index"),target())
                "master_skill" -> NativeDecisionAction.MasterSkill(n("index"),target())
                "order_change" -> NativeDecisionAction.OrderChange(n("front"),n("reserve"))
                "enemy_target" -> NativeDecisionAction.EnemyTarget(n("index"))
                "skills" -> NativeDecisionAction.Skills(value.getValue("actions").jsonArray.map { action(it.jsonObject) },
                    value["delayMillis"]?.jsonPrimitive?.long ?: 1500)
                "finish_skills" -> NativeDecisionAction.FinishSkills
                "cards" -> NativeDecisionAction.Cards(value.getValue("order").jsonArray.map { it.jsonPrimitive.int }, target())
                else -> error("Unsupported explicit external action")
            }
        }
    }
}
