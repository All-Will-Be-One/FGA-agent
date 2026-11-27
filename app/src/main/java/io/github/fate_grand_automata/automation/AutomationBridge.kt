package io.github.fate_grand_automata.automation

import android.content.Context
import io.github.fate_grand_automata.supportcatalog.ServantCatalog
import io.github.fate_grand_automata.supportcatalog.SupportPortraitImporter
import io.github.fate_grand_automata.supportcatalog.SupportServantStore
import kotlinx.coroutines.runBlocking
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.runner.*
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.decision.NativeBattleDecisionProvider
import io.github.fga.automation.*
import io.github.fate_grand_automata.automation.platform.PlatformLifecycle
import kotlinx.serialization.json.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import timber.log.Timber

/** Process-local adapter to the real, UI-shared ScriptManager. No game loop. */
object AutomationBridge {
    private val main=Handler(Looper.getMainLooper())
    private val instanceId=UUID.randomUUID().toString()
    private var manager=WeakReference<ScriptManager>(null)
    @Volatile private var owner:String?=null
    @Volatile private var activeRunner:ScriptManager?=null
    @Volatile private var activeRequest:BattleRunRequest?=null
    private var preferences:IPreferences?=null
    private var controller:AutomationController?=null
    private var dispatcher:RpcDispatcher?=null
    private var externalPlans:ExternalPlanProtocol?=null
    private var frames:ExternalFrameStore?=null
    private var initializationFailure:String?=null
    @Volatile private var partyJob: PartyJob? = null
    private data class PartyJob(
        val requestId: String, val reference: Int?, val plan: CalculatedParty?,
        val instance: String,
        @Volatile var state: String = "Running", @Volatile var step: String = "Starting",
        @Volatile var error: String? = null, @Volatile var errorCode: String? = null
    )

    private fun partyRequest(context: Context, prefs: IPreferences, op: String, q: JsonObject): JsonObject {
        val id = q["requestId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
            ?: throw RpcFailure("InvalidRequest", "A bounded requestId is required")
        val allowed = setOf("protocolVersion", "op", "requestId") +
            if (op == "party.status") emptySet() else setOf("action")
        require(q.keys == allowed) { "Unexpected Party Editor request fields" }
        if (op != "party.status") {
            val action = q.getValue("action")
            val reference = if (op == "party.rebuild") {
                action.jsonObject.takeIf { it.keys == setOf("referencePreset") }
                    ?.get("referencePreset")?.jsonPrimitive?.intOrNull?.takeIf { it in 1..14 }
                    ?: throw RpcFailure("InvalidRequest", "referencePreset must be a read-only preset from 1 to 14")
            } else null
            val plan = if (op == "party.apply") CalculatedParty.read(action) else null
            val previous = partyJob
            if (previous?.requestId == id && (previous.reference != reference || previous.plan != plan))
                throw RpcFailure("RequestConflict", "Same requestId, different formation or script")
            if (previous?.requestId != id) {
                if (executorBusy() || previous?.state == "Running")
                    throw RpcFailure("Busy", "Native runner is occupied")
                val runner = manager.get() ?: throw RpcFailure("RequiresUserConsent", "Prepare FGA native capture first")
                val job = PartyJob(id, reference, plan, instanceId)
                partyJob = job
                try {
                    runner.launchPartyEditor({ editor, canContinue ->
                        val progress: (String) -> Unit = { job.step = it }
                        if (reference != null) editor.rebuild(reference, canContinue, progress)
                        else {
                            val recipe = requireNotNull(plan)
                            progress("preflight: prepare exact friend servant image")
                            val store = SupportServantStore.inApp(context.filesDir)
                            if (store.read(recipe.support.servantId).isEmpty()) {
                                val catalog = ServantCatalog.fetch().singleOrNull { it.id == recipe.support.servantId }
                                    ?: throw RpcFailure("SupportUnavailable", "Friend servant ID is missing from the JP catalog")
                                runBlocking { SupportPortraitImporter.import(catalog, store) }
                            }
                            fun conflictCheck() {
                                val found = prefs.battleConfigs.firstOrNull { it.id == recipe.configId }
                                if (found != null && !recipeConfigMatches(found.export(), recipe))
                                    throw RpcFailure("ConfigurationConflict", "Generated config was modified; will not overwrite")
                            }
                            conflictCheck()
                            editor.apply(recipe.formation, canContinue, progress)
                            // Write the native config only after observing the saved in-game roster.
                            progress("installing native FGA skill command")
                            check(canContinue()) { "Party Editor no longer owns the runner" }
                            conflictCheck()
                            val config = prefs.battleConfigs.firstOrNull { it.id == recipe.configId }
                                ?: prefs.addBattleConfig(recipe.configId).also { it.import(recipeConfigValues(recipe)) }
                            check(recipeConfigMatches(config.export(), recipe)) { "Native script read-back disagreed" }
                            progress("verified saved XV and installed matching native script; no battle started")
                        }
                    }) { error ->
                        job.error = error?.message
                        job.errorCode = (error as? RpcFailure)?.code
                        job.state = if (error == null) "Completed" else "Failed"
                    }
                } catch (e: Exception) {
                    partyJob = previous
                    throw e
                }
            }
        }
        val job = partyJob?.takeIf { it.requestId == id } ?: throw RpcFailure("NotFound", "Unknown party job")
        return buildJsonObject {
            put("requestId", job.requestId)
            job.reference?.let { put("referencePreset", it) }
            put("targetPreset", 15); put("instanceId", job.instance)
            put("state", job.state); put("step", job.step)
            put("quiescent", job.state != "Running")
            job.error?.let { put("error", it) }
            job.errorCode?.let { put("errorCode", it) }
            job.plan?.let { put("actionSha256", it.actionSha256) }
            if (job.state == "Completed") job.plan?.let {
                put("configId", it.configId); put("scriptSha256", it.scriptSha256)
                put("readyToStart", false)
                if (it.supportAppendMax.isNotEmpty())
                    put("supportAppendMax", JsonArray(it.supportAppendMax.map(::JsonPrimitive)))
                if (it.supportAppendUnlocked.isNotEmpty())
                    put("supportAppendUnlocked", JsonArray(it.supportAppendUnlocked.map(::JsonPrimitive)))
            }
        }
    }

    private fun recipeConfigValues(plan: CalculatedParty): Map<String, Any> = mapOf(
        "autoskill_name" to plan.configName,
        "autoskill_cmd" to plan.skillCommand,
        "autoskill_party" to "14",
        "battle_config_server" to "Jp",
        "auto_choose_target" to false,
        "auto_skill_rearrange_cards" to "F",
        "support_mode" to "Preferred",
        "support_fallback" to "Manual",
        "support_friends_only" to true,
        "support_skill_max_1" to true,
        "support_skill_max_2" to true,
        "support_skill_max_3" to true,
        "support_pref_servant_list" to setOf("atlas:svt:${plan.support.servantId}")
    ) + (if (plan.supportAppendMax.isNotEmpty())
        mapOf("agent_support_append_max_slots" to plan.supportAppendMax.map(Int::toString).toSet())
    else emptyMap()) + (if (plan.supportAppendUnlocked.isNotEmpty())
        mapOf("agent_support_append_unlocked_slots" to plan.supportAppendUnlocked.map(Int::toString).toSet())
    else emptyMap())

    private fun recipeConfigMatches(values: Map<String, *>, plan: CalculatedParty) =
        recipeConfigValues(plan).all { (k, v) -> values[k] == v } &&
            (plan.supportAppendMax.isNotEmpty() || (values["agent_support_append_max_slots"] as? Set<*>).isNullOrEmpty()) &&
            (plan.supportAppendUnlocked.isNotEmpty() || (values["agent_support_append_unlocked_slots"] as? Set<*>).isNullOrEmpty())

    @Synchronized fun attach(value:ScriptManager) { manager=WeakReference(value) }
    @Synchronized fun executorBusy():Boolean = ProcessScriptRunGate.gate.currentReservation()!=null ||
        controller?.hasUnreleasedOwner()==true
    @Synchronized fun detach(value:ScriptManager) {
        if(manager.get()===value) manager.clear()
        if(activeRunner===value) owner?.let { controller?.uncertain(it,"ServiceDestroyed") }
    }

    @Synchronized fun initialize(context:Context,prefs:IPreferences) {
        if(controller!=null || initializationFailure!=null) return
        try {
            preferences=prefs
            val backend=object:NativeBattleBackend {
                override fun start(run:RunSnapshot,callback:NativeBattleCallback) {
                    val deadline=SystemClock.elapsedRealtime()+maxOf(0,run.createdAtMillis+run.limits.timeoutSeconds*1000-System.currentTimeMillis())
                    main.post {
                        // Stop may have arrived before this queued dispatch.
                        val current=controller?.status(runId=run.runId)
                        val remaining=deadline-SystemClock.elapsedRealtime()
                        if(remaining<=0) {
                            controller?.expire(run.runId)
                            callback.exit("DeadlineExceeded",0);return@post
                        }
                        if(current?.state==RunState.Stopping) {
                            callback.exit("CancelledBeforeStart",0);return@post
                        }
                        val runner=manager.get()
                        if(runner==null) {
                            callback.rejected("RequiresUserConsent","Open FGA Automation and prepare accessibility and screen capture in the normal UI")
                            return@post
                        }
                        val request=try {
                            runner.automationRequest(run.configId,run.limits.maxRuns,run.limits.timeoutSeconds,
                                SystemClock.elapsedRealtime()+remaining).copy(decisionMode=run.decisionMode)
                        } catch(e:Exception) {
                            callback.rejected((e as? BattleRunValidationException)?.code ?: "InvalidConfiguration",e.message ?: "Invalid native battle configuration")
                            return@post
                        }
                        try {
                            val result=runner.startAutomation(request,object:NativeAutomationListener {
                                override fun onStateChanged(state:NativeAutomationState) {
                                    if(state==NativeAutomationState.Starting) {
                                        // Only the successfully reserved API runner emits Starting.
                                        // A Busy result must never borrow the manual runner's input guard.
                                        activeRequest=request
                                        activeRunner=runner
                                        owner=run.runId
                                    }
                                    callback.state(RunState.valueOf(state.name))
                                }
                                override fun onExit(exit:NativeAutomationExit) {
                                    callback.exit(exit.reason,exit.completedRuns,exit.error,exit.outcome,exit.withdrawCount)
                                    runCatching { externalPlans?.close(run.runId,exit.reason) }
                                        .onFailure { initializationFailure="External-plan journal close failed: ${it.message}" }
                                    frames?.release(run.runId)
                                    if(owner==run.runId) { owner=null;activeRunner=null;activeRequest=null }
                                }
                                override fun onCleanupFailure(error:String) {
                                    controller?.uncertain(run.runId,"NativeCleanupUnconfirmed",error)
                                }
                            })
                            when(result) {
                                NativeStartResult.Accepted -> {
                                    main.postDelayed({controller?.expire(run.runId)},maxOf(0,deadline-SystemClock.elapsedRealtime()))
                                }
                                NativeStartResult.Busy -> {
                                    callback.rejected("Busy","Native UI or another task owns the runner")
                                    // Observe the foreign runner's real drain; never control it.
                                    runner.onProcessRunnerIdle { callback.exit("Busy",0) }
                                }
                                NativeStartResult.RequiresUserConsent -> { owner=null;activeRunner=null;activeRequest=null;callback.rejected("RequiresUserConsent","Accessibility or screen capture must be prepared by the user") }
                                is NativeStartResult.Rejected -> { owner=null;activeRunner=null;activeRequest=null;callback.rejected(result.code,result.message) }
                            }
                        } catch(e:Exception) {
                            // A native runner may already own a thread. Never label
                            // an exception after dispatch as a safe rejection.
                            controller?.uncertain(run.runId,"NativeDispatchUnconfirmed",e.message)
                        }
                    }
                }
                override fun control(runId:String,op:String) {
                    main.post {
                        if(controller?.permitControl(runId,op)!=true) return@post
                        val runner=activeRunner
                        if(owner!=runId || runner==null) {
                            // A queued start handles Stopping without dispatch.
                            if(controller?.status(runId=runId)?.state!=RunState.Stopping) controller?.uncertain(runId,"RunnerUnavailable")
                            return@post
                        }
                        val accepted=when(op) {
                            "stop" -> runner.stopAutomation()
                            "pause" -> runner.pauseAutomation(true)
                            "resume" -> runner.pauseAutomation(false)
                            else -> false
                        }
                        if(!accepted) controller?.uncertain(runId,"NativeControlUnconfirmed")
                    }
                }
            }
            controller=AutomationController(FileJournalStore(File(context.filesDir,"automation/runs-v1.json")) { directory ->
                val fd=Os.open(directory.path,OsConstants.O_RDONLY,0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            },backend,processInstanceId=instanceId)
            externalPlans=ExternalPlanProtocol(FileExternalJournalStore(File(context.filesDir,"automation/external-plans-v1.json")) { directory ->
                val fd=Os.open(directory.path,OsConstants.O_RDONLY,0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            },::permitExternalPlan)
            // initialize runs once per Android process. A new controller object alone
            // never proves a live owner absent; use the actual process gate/runner.
            val executorAbsent=ProcessScriptRunGate.gate.currentReservation()==null &&
                activeRunner==null && owner==null && manager.get()?.isExecutorBusy!=true
            controller?.reconcilePreviousProcess(executorAbsent)?.forEach { id ->
                runCatching { externalPlans?.close(id,"ProcessRestart",executorGone=true) }
                    .onFailure { Timber.w(it,"Interrupted external session cleanup failed") }
            }
            frames=ExternalFrameStore(File(context.cacheDir,"automation-frames"),BuildConfig.APPLICATION_ID+".automation")
            dispatcher=RpcDispatcher(requireNotNull(controller),{
                buildJsonObject {
                    put("backend","fga-android-native");put("applicationId",BuildConfig.APPLICATION_ID)
                    put("protocolVersion",1);put("versionName",BuildConfig.VERSION_NAME)
                    put("upstreamRevision",BuildConfig.AUTOMATION_UPSTREAM_REVISION)
                    put("candidateRevision",BuildConfig.AUTOMATION_SOURCE_REVISION)
                    put("instanceId",instanceId);put("prepared",manager.get()?.isAutomationPrepared==true)
                    put("executorBusy",executorBusy())
                    put("capabilities",buildJsonObject {
                        listOf("battle","farm","configs","start","status","pause","resume","stop","events").forEach { put(it,true) }
                        put("perStepInput",false);put("smartStrategy",false);put("externalPlans",true);put("liveVerified",false)
                    })
                    put("externalPlanProtocolVersion",5)
                    put("platformLifecycleVersion",1)
                    put("captureDiagnosticVersion",1)
                    put("partyRebuildVersion",1)
                    put("calculatedPartyVersion",1)
                    put("supportAppendMaxVersion",1)
                    put("supportAppendUnlockedVersion",1)
                    put("decisionModes",JsonArray(listOf("script","smart").map(::JsonPrimitive)))
                    put("externalPlanLimits",buildJsonObject {
                        put("strategyOwner","FGOAgent");put("frameTransformOwner","FGOAgent")
                        put("nativeTargetDialogEvidence",false);put("acceptedCardCountEvidence",false)
                        put("maxCardCandidates",8);put("candidateCardDelivery",true);put("automaticCardFill",false);put("nativeEnemyTargetSlots",3)
                        put("classicPolicy",false);put("observationTtlSeconds",JsonNull)
                    })
                    put("regions",JsonArray(listOf("CN","JP","NA","TW","KR").map(::JsonPrimitive)))
                    put("transport","adb-shell-content-provider");put("callerPolicy","own_uid_or_shell_2000")
                    put("resources","none");put("resumeAfterProcessDeath",false)
                }
            },{
                automationConfigurations(prefs.battleConfigs,{ config ->
                    config.server ?: runCatching {manager.get()?.automationRequest(config.id,1,300)?.server}.getOrNull()
                },::region)
            },platform={ op,q -> PlatformLifecycle.request(context,op,q) },externalPlans=::externalRequest, partyEditor={ op,q -> partyRequest(context,prefs,op,q) },
                diagnostic={
                    if(executorBusy()) throw RpcFailure("Busy","Capture diagnostic requires an idle runner")
                    val runner=manager.get() ?: throw RpcFailure("RequiresUserConsent","Prepare the native capture service first")
                    runner.captureDiagnostic(requireNotNull(frames))
                })
        } catch(e:Exception) { initializationFailure=e.message ?: "Journal unavailable" }
    }
    @Synchronized fun request(text:String):String = dispatcher?.request(text) ?: buildJsonObject {
        put("ok",false);put("protocolVersion",1)
        putJsonObject("error") { put("code","JournalUnavailable");put("message",initializationFailure ?: "Provider not initialized") }
    }.toString()
    private fun permitExternalPlan(id:String) {
        val run=controller?.status(runId=id) ?: throw RpcFailure("RunnerUnavailable","Native controller unavailable")
        if(run.decisionMode!="smart" || owner!=id || activeRequest?.decisionMode!="smart" || activeRunner==null ||
            run.quiescent || run.reconciliationRequired || run.state !in setOf(RunState.Starting,RunState.Running,RunState.Paused) || run.pendingControl=="stop")
            throw RpcFailure("ExternalPlanNotPermitted","External plans require this owned running Smart task")
        if(System.currentTimeMillis()>=run.createdAtMillis+run.limits.timeoutSeconds*1000)
            throw RpcFailure("DeadlineExceeded","Bounded native task deadline elapsed")
    }
    @Synchronized fun createDecisionProvider():NativeBattleDecisionProvider {
        val id=owner ?: throw RpcFailure("RunnerUnavailable","No API-owned native executor")
        permitExternalPlan(id)
        val protocol=externalPlans ?: error("External-plan journal unavailable")
        protocol.open(id)
        return ExternalPlanReceiver(id,protocol,requireNotNull(frames),requireOwner={ permitExternalPlan(id) },acceptedStop={
            val run=controller?.status(runId=id)
            run!=null && acceptedOwnedSmartStop(run,owner,activeRequest?.decisionMode=="smart",activeRunner!=null)
        })
    }
    private fun externalRequest(op:String,q:JsonObject):JsonObject {
        val protocol=externalPlans ?: throw RpcFailure("JournalUnavailable","External-plan journal unavailable")
        fun s(key:String)=q[key]?.jsonPrimitive?.content ?: throw RpcFailure("InvalidRequest","Missing $key")
        val id=s("runId")
        return when(op) {
            "decision.next" -> protocol.next(id)
            "decision.status" -> buildJsonObject { put("runId",id);put("receipt",Json.encodeToJsonElement(protocol.status(id,s("planId")))) }
            "decision.submit" -> buildJsonObject {
                put("runId",id)
                put("receipt",Json.encodeToJsonElement(protocol.submit(ExternalPlan(id,s("planId"),s("battleId"),s("turnId"),s("phase"),s("observationId"),q.getValue("action").jsonObject))))
            }
            else -> throw RpcFailure("InvalidOperation","Unsupported external-plan operation")
        }
    }
    @Synchronized fun frame(token:String):File = frames?.read(token) ?: throw java.io.FileNotFoundException("Capture unavailable")
    fun observe(type:String,detail:String) {
        val id=owner ?: return
        // Observation is best effort; it must never alter a native battle decision.
        runCatching { controller?.nativeEvent(id,type,detail) }
    }
    private fun region(server:GameServer):String = when(server) {
        is GameServer.Jp -> "JP"
        is GameServer.En -> "NA"
        GameServer.Cn -> "CN"
        GameServer.Tw -> "TW"
        GameServer.Kr -> "KR"
    }
}
