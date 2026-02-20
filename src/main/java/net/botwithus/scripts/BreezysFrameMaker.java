package net.botwithus.scripts;

import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.inventories.Backpack;
import net.botwithus.rs3.game.inventories.Bank;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.minimenu.actions.ComponentAction;
import net.botwithus.rs3.game.minimenu.actions.ObjectAction;
import net.botwithus.rs3.game.skills.Skill;
import net.botwithus.rs3.game.skills.Skills;
import net.botwithus.rs3.imgui.ImGui;
import net.botwithus.rs3.script.Execution;
import net.botwithus.rs3.script.LoopingScript;
import net.botwithus.rs3.script.ScriptGraphicsContext;
import net.botwithus.rs3.script.config.ScriptConfig;

import java.util.Map;

/**
 * ============================================================
 *  Breezy's Frame Maker
 *  BotWithUs API  |  JDK 20
 * ============================================================
 *
 *  TWO MODES (toggled via UI checkbox):
 *
 *  FRAMES ONLY (default):
 *    Preset must contain Refined Planks.
 *    Route: Bank Chest (load preset) -> Woodworking Bench -> repeat.
 *
 *  FULL PIPELINE:
 *    Preset must contain raw Logs.
 *    Route: Bank Chest -> Sawmill (logs->planks) -> Sawmill (planks->refined)
 *                      -> Woodworking Bench (refined->frames) -> repeat.
 *
 *  AUTO-DETECT PRIORITY (highest XP tier first):
 *    Elder > Magic > Yew > Mahogany > Acadia > Maple > Teak > Willow > Oak > Wooden
 *    Detects logs / planks / refined planks in inventory and picks the correct step.
 *
 * ============================================================
 *
 *  CONFIRMED OBJECT / INTERFACE IDs
 *  ---------------------------------------------------------------
 *  Bank Chest      : 125239  (3283,3555)  OBJECT4 = Load preset
 *  Sawmill         : 125240  (3281,3550)  OBJECT1 = Open menu
 *  Woodworking     : 125054  (3282,3550)  OBJECT1 = Open menu
 *  Sawmill IF      : 1370 (also 1371 on first open -- we check both)
 *  Workbench IF    : 1371
 *  Progress IF     : 1251  (open during crafting, closes when done)
 *  Construct btn   : DIALOGUE 0 -1 89784350  (same for all three stations)
 *  ---------------------------------------------------------------
 *
 *  ITEM ID TABLE
 *  ---------------------------------------------------------------
 *  Wood       Log ID   Plank ID  Refined ID   Frame ID
 *  Wooden     1511     960       54444        54452
 *  Oak        1521     8778      54446        54454
 *  Willow     1519     0*        54836        54848   (*willow plank ID unconfirmed)
 *  Teak       6333     8780      54448        54456
 *  Maple      1517     54862     54838        54850
 *  Acadia     40285    54864     54840        54852
 *  Mahogany   6332     8782      54450        54458
 *  Yew        1515     54866     54842        54854
 *  Magic      1513     54868     54844        54856
 *  Elder      29556    54870     54846        54858
 *  ---------------------------------------------------------------
 */
public class BreezysFrameMaker extends LoopingScript {

    // =========================================================================
    //  OBJECT / INTERFACE CONSTANTS
    // =========================================================================
    private static final int  BANK_CHEST_ID   = 125239;
    private static final int  BANK_CHEST_X    = 3283;
    private static final int  BANK_CHEST_Y    = 3555;

    private static final int  SAWMILL_ID      = 125240;
    private static final int  SAWMILL_X       = 3281;
    private static final int  SAWMILL_Y       = 3550;

    private static final int  WORKBENCH_ID    = 125054;
    private static final int  WORKBENCH_X     = 3282;
    private static final int  WORKBENCH_Y     = 3550;

    private static final int  SAWMILL_IF      = 1370;
    private static final int  WORKBENCH_IF    = 1371;
    private static final int  PROGRESS_IF     = 1251;
    private static final int  DIALOGUE_PARAM3 = 89784350;

    // Safety timeouts per processing step
    private static final long TIMEOUT_LOGS_TO_PLANKS    = 40_000L;
    private static final long TIMEOUT_PLANKS_TO_REFINED = 20_000L;
    private static final long TIMEOUT_REFINED_TO_FRAMES = 90_000L;

    // Price cache: tries Wiki API first, falls back to static GE estimates if sandbox blocks HTTP
    // Static fallback frame GE prices (gp). Update when prices shift significantly.
    // Formula: 12 logs -> 12 planks -> 3 refined planks -> 1 frame
    private static final Map<Integer, Integer> FALLBACK_FRAME_PRICES = Map.of(
        54452,  44000,  // Wooden frame
        54454,  35400,  // Oak frame
        54848,  38500,  // Willow frame
        54456,  41200,  // Teak frame
        54850,  25800,  // Maple frame
        54852,  48900,  // Acadia frame
        54458,  52100,  // Mahogany frame
        54854,  58400,  // Yew frame
        54856,  64200,  // Magic frame
        54858, 192500   // Elder frame
    );

    // Cost of 12 logs (one frame's worth of input material)
    private static final Map<Integer, Integer> LOG_COSTS = Map.of(
        54452,    312,  // Wooden  (12x logs @ ~26 gp)
        54454,   7092,  // Oak     (12x logs @ ~591 gp)
        54848,   2976,  // Willow  (12x logs @ ~248 gp)
        54456,   1260,  // Teak    (12x logs @ ~105 gp)
        54850,   4128,  // Maple   (12x logs @ ~344 gp)
        54852,  14400,  // Acadia  (12x logs @ ~1200 gp)
        54458,   5460,  // Mahogany(12x logs @ ~455 gp)
        54854,   2004,  // Yew     (12x logs @ ~167 gp)
        54856,   4560,  // Magic   (12x logs @ ~380 gp)
        54858, 106020   // Elder   (12x logs @ ~8835 gp)
    );

    // =========================================================================
    //  WOOD TIERS  (highest XP first)
    // =========================================================================
    private enum WoodType {
        //         name          logId  plankId  refinedId  frameId
        ELDER    ("Elder",       29556, 54870,   54846,     54858),
        MAGIC    ("Magic",       1513,  54868,   54844,     54856),
        YEW      ("Yew",         1515,  54866,   54842,     54854),
        MAHOGANY ("Mahogany",    6332,  8782,    54450,     54458),
        ACADIA   ("Acadia",      40285, 54864,   54840,     54852),
        MAPLE    ("Maple",       1517,  54862,   54838,     54850),
        TEAK     ("Teak",        6333,  8780,    54448,     54456),
        WILLOW   ("Willow",      1519,  5486,    54836,     54848),
        OAK      ("Oak",         1521,  8778,    54446,     54454),
        WOODEN   ("Wooden",      1511,  960,     54444,     54452);

        final String name;
        final int    logId, plankId, refinedId, frameId;

        WoodType(String name, int logId, int plankId, int refinedId, int frameId) {
            this.name      = name;
            this.logId     = logId;
            this.plankId   = plankId;
            this.refinedId = refinedId;
            this.frameId   = frameId;
        }
    }

    // =========================================================================
    //  ENUMS
    // =========================================================================
    private enum Stage { LOGS, PLANKS, REFINED, UNKNOWN }

    private enum State {
        IDLE,
        LOAD_PRESET,
        LOGS_TO_PLANKS,       // Sawmill step A: logs -> planks
        PLANKS_TO_REFINED,    // Sawmill step B: planks -> refined planks
        REFINED_TO_FRAMES,    // Workbench step C: refined planks -> frames
        DONE
    }

    // =========================================================================
    //  FIELDS
    // =========================================================================
    private State    state        = State.IDLE;
    private WoodType currentWood     = null;
    private WoodType lastKnownWood   = null; // persists between batches for GP display
    private Stage    currentStage = Stage.UNKNOWN;

    private boolean running      = false;
    private boolean fullPipeline = false;
    private boolean randomAfk    = false;

    private int  totalBatches       = 0;
    private int  totalFramesCrafted = 0;
    private int  presetRetries      = 0;   // stop if bank chest fails too many times

    private int  startXp     = 0;
    private long startTimeMs = 0;

    // GP / profit tracking

    private final java.util.Random random = new java.util.Random();

    // =========================================================================
    //  CONSTRUCTOR
    // =========================================================================
    public BreezysFrameMaker(String name, ScriptConfig config, ScriptDefinition scriptDefinition) {
        super(name, config, scriptDefinition);
        this.sgc = new FrameCrafterUI(getConsole(), this);
    }

    // =========================================================================
    //  MAIN LOOP
    // =========================================================================
    @Override
    public void onLoop() {
        if (!running) { Execution.delay(300); return; }

        if (randomAfk && random.nextInt(50) == 0) {
            long pause = random.nextLong(5000, 15000);
            println("[FrameCrafter] AFK break for " + String.format("%.1f", pause / 1000.0) + "s...");
            Execution.delay(pause);
        }

        detectInventory();

        switch (state) {

            case IDLE -> state = State.LOAD_PRESET;

            // ------------------------------------------------------------------
            case LOAD_PRESET -> {
                println("[FrameCrafter] Loading preset from Bank Chest...");
                randomDelay(400, 900);
                boolean clicked = MiniMenu.interact(
                    ObjectAction.OBJECT4.getType(), BANK_CHEST_ID, BANK_CHEST_X, BANK_CHEST_Y
                );
                if (clicked) {
                    Execution.delayUntil(5000, Bank::isOpen);
                    Execution.delayUntil(5000, () -> !Bank.isOpen());
                    Execution.delay(600);
                    detectInventory();

                    if (currentWood == null || currentStage == Stage.UNKNOWN) {
                        println("[FrameCrafter] No recognised items after preset load. Finished.");
                        state = State.DONE;
                        return;
                    }
                    presetRetries = 0;
                    println("[FrameCrafter] Preset loaded: " + currentWood.name + " / " + currentStage);
                    state = resolveNextState();
                } else {
                    presetRetries++;
                    println("[FrameCrafter] Failed to click Bank Chest (attempt " + presetRetries + "/5), retrying...");
                    if (presetRetries >= 5) {
                        println("[FrameCrafter] Too many Bank Chest failures. Stopping.");
                        state = State.DONE;
                    }
                    Execution.delay(1200);
                }
            }

            // ------------------------------------------------------------------
            case LOGS_TO_PLANKS -> {
                if (currentStage != Stage.LOGS) { state = resolveNextState(); return; }
                println("[FrameCrafter] Sawmill: logs -> planks...");
                if (openMenu(SAWMILL_ID, SAWMILL_X, SAWMILL_Y, SAWMILL_IF)) {
                    if (clickConstruct()) {
                        waitForProgress(TIMEOUT_LOGS_TO_PLANKS);
                        detectInventory();
                        if (currentWood == null || currentStage == Stage.UNKNOWN) {
                            println("[FrameCrafter] Out of logs after processing. Finished.");
                            state = State.DONE;
                        } else {
                            state = resolveNextState();
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            case PLANKS_TO_REFINED -> {
                if (currentStage != Stage.PLANKS) { state = resolveNextState(); return; }
                println("[FrameCrafter] Sawmill: planks -> refined planks...");
                if (openMenu(SAWMILL_ID, SAWMILL_X, SAWMILL_Y, SAWMILL_IF)) {
                    if (clickConstruct()) {
                        waitForProgress(TIMEOUT_PLANKS_TO_REFINED);
                        detectInventory();
                        if (currentWood == null || currentStage == Stage.UNKNOWN) {
                            println("[FrameCrafter] Out of planks after processing. Finished.");
                            state = State.DONE;
                        } else {
                            state = resolveNextState();
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            case REFINED_TO_FRAMES -> {
                if (currentStage != Stage.REFINED) { state = resolveNextState(); return; }
                println("[FrameCrafter] Workbench: refined planks -> frames...");
                if (!Interfaces.isOpen(WORKBENCH_IF)) {
                    if (!openMenu(WORKBENCH_ID, WORKBENCH_X, WORKBENCH_Y, WORKBENCH_IF)) return;
                }
                if (clickConstruct()) {
                    waitForProgress(TIMEOUT_REFINED_TO_FRAMES);
                    totalBatches++;
                    totalFramesCrafted += 28;
                    println("[FrameCrafter] Batch #" + totalBatches + " complete.");
                    detectInventory();
                    // After crafting, inventory will be frames -- go bank for more
                    // But if preset is empty the LOAD_PRESET state will catch it and stop
                    state = State.LOAD_PRESET;
                }
            }

            // ------------------------------------------------------------------
            case DONE -> {
                println("[FrameCrafter] No items remaining. Logging out...");
                logout();
                running = false;
                state   = State.IDLE;
                println("[FrameCrafter] Finished. Total batches: " + totalBatches);
            }
        }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Detects what wood type and pipeline stage is currently in the inventory. */
    private void detectInventory() {
        for (WoodType w : WoodType.values()) {
            if (Backpack.contains(w.refinedId)) { currentWood = w; lastKnownWood = w; currentStage = Stage.REFINED; return; }
        }
        for (WoodType w : WoodType.values()) {
            if (w.plankId > 0 && Backpack.contains(w.plankId)) { currentWood = w; lastKnownWood = w; currentStage = Stage.PLANKS; return; }
        }
        for (WoodType w : WoodType.values()) {
            if (Backpack.contains(w.logId)) { currentWood = w; lastKnownWood = w; currentStage = Stage.LOGS; return; }
        }
        currentWood  = null;
        currentStage = Stage.UNKNOWN;
    }

    /**
     * Determines the next state based on current inventory stage and pipeline mode.
     * In frames-only mode, always jumps straight to REFINED_TO_FRAMES.
     */
    private State resolveNextState() {
        if (currentWood == null || currentStage == Stage.UNKNOWN) return State.DONE;
        if (!fullPipeline) return State.REFINED_TO_FRAMES;
        return switch (currentStage) {
            case LOGS    -> State.LOGS_TO_PLANKS;
            case PLANKS  -> State.PLANKS_TO_REFINED;
            case REFINED -> State.REFINED_TO_FRAMES;
            default      -> State.DONE;
        };
    }

    /** Clicks a scene object and waits for its interface to open. Returns true if opened. */
    private boolean openMenu(int objId, int x, int y, int interfaceId) {
        randomDelay(300, 800);
        boolean clicked = MiniMenu.interact(ObjectAction.OBJECT1.getType(), objId, x, y);
        println("[FrameCrafter] Click obj " + objId + " result: " + clicked);
        if (!clicked) { Execution.delay(1200); return false; }
        boolean opened = Execution.delayUntil(5000, () ->
            Interfaces.isOpen(interfaceId) || Interfaces.isOpen(WORKBENCH_IF) || Interfaces.isOpen(SAWMILL_IF)
        );
        if (!opened) {
            println("[FrameCrafter] Interface did not open, retrying...");
            Execution.delay(1000);
            return false;
        }
        println("[FrameCrafter] Interface open.");
        return true;
    }

    /** Fires the Construct dialogue action. Returns true if the progress interface opened. */
    private boolean clickConstruct() {
        randomDelay(350, 750);
        println("[FrameCrafter] Clicking Construct...");
        boolean clicked = MiniMenu.interact(ComponentAction.DIALOGUE.getType(), 0, -1, DIALOGUE_PARAM3);
        println("[FrameCrafter] Construct click result: " + clicked);
        if (!clicked) { Execution.delay(1000); return false; }
        boolean started = Execution.delayUntil(5000, () -> Interfaces.isOpen(PROGRESS_IF));
        if (!started) {
            println("[FrameCrafter] Progress interface never opened, retrying...");
            return false;
        }
        return true;
    }

    /** Waits for the progress interface 1251 to close, with a safety timeout. */
    private void waitForProgress(long timeoutMs) {
        println("[FrameCrafter] Waiting for crafting to finish (timeout: " + (timeoutMs / 1000) + "s)...");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (Interfaces.isOpen(PROGRESS_IF) && System.currentTimeMillis() < deadline && running) {
            Execution.delay(500);
        }
        if (Interfaces.isOpen(PROGRESS_IF)) {
            println("[FrameCrafter] WARNING: progress timed out, continuing anyway.");
        }
        Execution.delay(600);
    }

    private void logout() {
        MiniMenu.interact(ComponentAction.COMPONENT.getType(), 1, 8 | (182 << 16), 0);
        Execution.delay(3000);
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void randomDelay(long minMs, long maxMs) {
        Execution.delay(minMs + (long)(Math.random() * (maxMs - minMs)));
    }

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================
    @Override
    public void onActivation() {
        println("[FrameCrafter] Loaded. Press Start in the panel.");
        Client.setAntiAFK(true);
        running = false;
        state   = State.IDLE;
    }

    @Override
    public void onDeactivation() {
        println("[FrameCrafter] Unloaded. Total batches: " + totalBatches);
        Client.setAntiAFK(false);
    }

    // =========================================================================
    //  IMGUI UI PANEL
    // =========================================================================
    private class FrameCrafterUI extends ScriptGraphicsContext {

        FrameCrafterUI(net.botwithus.rs3.script.ScriptConsole console, BreezysFrameMaker ignored) {
            super(console);
        }

        @Override
        public void drawSettings() {
            ImGui.Begin("Fort Forinthry Frame Crafter", 0);

            // ---- Status ----
            ImGui.Text("Status  : %s", running ? "RUNNING" : "STOPPED");
            ImGui.Text("State   : %s", state.name());
            ImGui.Text("Wood    : %s", currentWood  != null ? currentWood.name    : "None");
            ImGui.Text("Stage   : %s", currentStage != null ? currentStage.name() : "None");
            ImGui.Text("Batches : %d", totalBatches);

            ImGui.Separator();

            // ---- Pipeline toggle + preset instructions ----
            fullPipeline = ImGui.Checkbox("Full Pipeline (Logs to Frames)", fullPipeline);
            if (fullPipeline) {
                ImGui.Text("  Route  : Sawmill, Sawmill, Workbench");
                ImGui.Text("  Preset : Fill preset with raw logs");
            } else {
                ImGui.Text("  Route  : Workbench only");
                ImGui.Text("  Preset : Fill preset with refined planks");
            }

            ImGui.Separator();

            // ---- AFK toggle ----
            randomAfk = ImGui.Checkbox("Random AFK breaks (1/50 chance)", randomAfk);

            ImGui.Separator();

            // ---- Start / Stop ----
            if (running) {
                if (ImGui.Button("Stop")) {
                    running = false;
                    println("[FrameCrafter] Stopped by user.");
                }
            } else {
                if (ImGui.Button("Start")) {
                    startXp            = new Skill(Skills.CONSTRUCTION).getExperience();
                    startTimeMs        = System.currentTimeMillis();
                    totalFramesCrafted = 0;
                    running            = true;
                    state              = State.LOAD_PRESET;
                    println("[FrameCrafter] Started. Pipeline: " + (fullPipeline ? "FULL" : "FRAMES ONLY"));
                }
            }

            ImGui.Separator();

            // ---- XP / Stats ----
            int    curXp     = new Skill(Skills.CONSTRUCTION).getExperience();
            int    curLvl    = new Skill(Skills.CONSTRUCTION).getLevel();
            int    xpGained  = curXp - startXp;
            long   elapsedMs = System.currentTimeMillis() - startTimeMs;
            double hrs       = elapsedMs / 3_600_000.0;
            int    xpPerHr   = hrs > 0 ? (int)(xpGained / hrs) : 0;

            ImGui.Text("Construction  : %d", curLvl);
            ImGui.Text("Current XP    : %,d", curXp);
            ImGui.Text("XP Gained     : %,d", xpGained);
            ImGui.Text("XP / hr       : %,d", xpPerHr);

            // ---- GP / Profit ----
            WoodType displayWood = currentWood != null ? currentWood : lastKnownWood;
            if (displayWood != null) {
                int  framePrice    = FALLBACK_FRAME_PRICES.getOrDefault(displayWood.frameId, 0);
                int  logCost       = LOG_COSTS.getOrDefault(displayWood.frameId, 0);
                int  profitPerFrame = fullPipeline ? framePrice - logCost : framePrice;
                long totalProfit   = (long) totalFramesCrafted * profitPerFrame;
                int  profitPerHr   = (hrs > 0 && totalFramesCrafted > 0) ? (int)(totalProfit / hrs) : 0;
                ImGui.Text("Frame Price   : %,d gp", framePrice);
                if (fullPipeline) {
                    ImGui.Text("Log Cost      : %,d gp (x12)", logCost);
                    ImGui.Text("Profit/Frame  : %,d gp", profitPerFrame);
                } else {
                    ImGui.Text("Revenue/Frame : %,d gp", framePrice);
                }
                if (totalFramesCrafted > 0) {
                    ImGui.Text("Profit / hr   : %,d gp", profitPerHr);
                    ImGui.Text("Total Profit  : %,d gp", totalProfit);
                } else {
                    ImGui.Text("Profit / hr   : Waiting for first batch...");
                }
            }

            ImGui.Text("Time Running  : %s", startTimeMs > 0 ? formatTime(elapsedMs) : "00:00:00");

            ImGui.End();
        }
    }
}
