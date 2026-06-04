package at.hannibal2.skyhanni.features.event.carnival

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.data.InteractClickType
import at.hannibal2.skyhanni.events.BlockClickEvent
import at.hannibal2.skyhanni.events.DataWatcherUpdatedEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.ServerBlockChangeEvent
import at.hannibal2.skyhanni.events.chat.SkyHanniChatEvent
import at.hannibal2.skyhanni.events.entity.EntityCustomNameUpdateEvent
import at.hannibal2.skyhanni.events.entity.EntityEnterWorldEvent
import at.hannibal2.skyhanni.events.entity.EntityEquipmentChangeEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniRenderWorldEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.features.event.carnival.FruitDiggingSolverEngine.BoardPos
import at.hannibal2.skyhanni.features.event.carnival.FruitDiggingSolverEngine.Kind
import at.hannibal2.skyhanni.features.event.carnival.FruitDiggingSolverEngine.Mode
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.BlockUtils.getBlockAt
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.EntityUtils
import at.hannibal2.skyhanni.utils.EntityUtils.getWornSkullTexture
import at.hannibal2.skyhanni.utils.ItemUtils.getLore
import at.hannibal2.skyhanni.utils.ItemUtils.getSkullTexture
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.NumberUtil.formatPercentage
import at.hannibal2.skyhanni.utils.RegexUtils.findMatcher
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RegexUtils.matches
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.SafeItemStack
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getItemId
import at.hannibal2.skyhanni.utils.StringUtils
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import at.hannibal2.skyhanni.utils.collection.RenderableCollectionUtils.addString
import at.hannibal2.skyhanni.utils.compat.EntityCompat.getStandHelmet
import at.hannibal2.skyhanni.utils.compat.formattedTextCompatLeadingWhiteLessResets
import at.hannibal2.skyhanni.utils.compat.formattedTextCompatLessResets
import at.hannibal2.skyhanni.utils.getLorenzVec
import at.hannibal2.skyhanni.utils.itemType
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawDynamicText
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawWaypointFilled
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import java.awt.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
@SkyHanniModule
object CarnivalFruitDiggingSolver {

    private val config get() = SkyHanniMod.feature.event.carnival
    private val engine = FruitDiggingSolverEngine()

    private val patternGroup = RepoPattern.group("event.carnival.fruit-digging")

    /**
     * REGEX-TEST: [NPC] Carnival Pirateman: Good luck, matey!
     */
    private val startPattern by patternGroup.pattern(
        "start",
        "\\[NPC] Carnival Pirateman: Good luck, matey!",
    )

    /**
     * REGEX-TEST: MINES! There is 1 bomb hidden nearby.
     * REGEX-TEST: MINES! There are 3 bombs hidden nearby.
     */
    private val minesPattern by patternGroup.pattern(
        "mines",
        "MINES! There (?:is|are) (?<amount>\\d+) bombs? hidden nearby\\.",
    )

    /**
     * REGEX-TEST: TREASURE! There is a Durian nearby.
     * REGEX-TEST: TREASURE! There is an Apple nearby.
     */
    private val treasurePattern by patternGroup.pattern(
        "treasure",
        "TREASURE! There is an? (?<fruit>.*) nearby\\.",
    )

    /**
     * REGEX-TEST: TREASURE! There are no fruits nearby!
     * REGEX-TEST: ANCHOR! There are no fruits nearby!
     */
    private val noFruitsNearbyPattern by patternGroup.pattern(
        "no-fruits-nearby",
        "(?<mode>TREASURE|ANCHOR)! There are no fruits nearby!",
    )

    /**
     * REGEX-TEST: Pomegranate (+300)
     * REGEX-TEST: Bomb
     * REGEX-TEST: Rum
     */
    private val revealFruitPattern by patternGroup.pattern(
        "reveal",
        "^(?<name>[A-Za-z ]+)(?: \\(\\+\\d+\\))?$",
    )

    /**
     * REGEX-TEST: /texture/a76a2811d1e176a07b6d0a657b910f134896ce30850f6e80c7c83732d85381ea
     */
    private val textureIdPattern by patternGroup.pattern(
        "texture-id",
        "/texture/(?<id>[a-f0-9]+)",
    )

    // Captures the "(+123)" effective-value suffix the server shows next to a revealed fruit's name.
    /**
     * REGEX-TEST: Pomegranate (+300)
     * REGEX-TEST: Bomb (+100)
     */
    private val shownValuePattern by patternGroup.pattern(
        "shown-value",
        "\\(\\+(?<value>\\d+)\\)",
    )

    private val boardBox = AABB(
        FruitDiggingSolverEngine.MIN_X.toDouble(),
        FruitDiggingSolverEngine.BOARD_Y.toDouble(),
        FruitDiggingSolverEngine.MIN_Z.toDouble(),
        FruitDiggingSolverEngine.MAX_X + 1.0,
        FruitDiggingSolverEngine.BOARD_Y + 4.0,
        FruitDiggingSolverEngine.MAX_Z + 1.0,
    )

    private val knownFruitTextureIds = mapOf(
        "a76a2811d1e176a07b6d0a657b910f134896ce30850f6e80c7c83732d85381ea" to Kind.BOMB,
        "407b275d28b927b1bf7f6dd9f45fbdad2af8571c54c8f027d1bff6956fbf3c16" to Kind.RUM,
        "f363a62126a35537f8189343a22660de75e810c6ac004a7d3da65f1c040a839" to Kind.MANGO,
        "17ea278d6225c447c5943d652798d0bbbd1418434ce8c54c54fdac79994ddd6c" to Kind.APPLE,
        "efe4ef83baf105e8dee6cf03dfe7407f1911b3b9952c891ae34139560f2931d6" to Kind.WATERMELON,
        "40824d18079042d5769f264f44394b95b9b99ce689688cc10c9eec3f882ccc08" to Kind.POMEGRANATE,
        "10ceb1455b471d016a9f06d25f6e468df9fcf223e2c1e4795b16e84fcca264ee" to Kind.COCONUT,
        "c92b099a62cd2fbf8ada09dec145c75d7fda4dc57b968bea3a8fa11e37aa48b2" to Kind.CHERRY,
        "ac268d36c2c6047ffeec00124096376b56dbb4d756a55329363a1b27fcd659cd" to Kind.DURIAN,
        "3cc761bcb0579763d9b8ab6b7b96fa77eb6d9605a804d838fec39e7b25f95591" to Kind.DRAGONFRUIT,
    )
    private val debugged = mutableSetOf<String>()

    private var display = emptyList<Renderable>()

    // A round is counted from left-clicked dig's, because block changes are not reliable.
    private val pendingDigs = mutableMapOf<BoardPos, SimpleTimeMark>()
    private val pendingDigTimeout = 15.seconds
    private var lastClickedPos: BoardPos? = null
    private var lastConfirmedDigTime = SimpleTimeMark.farPast()

    private var stableRecommendation: FruitDiggingSolverEngine.Recommendation? = null
    private var stableRound = -1
    private val recommendationSettleDelay = 700.milliseconds
    private var recommendationHiddenUntil = SimpleTimeMark.farPast()

    @HandleEvent
    fun onChat(event: SkyHanniChatEvent.Allow) {
        if (!config.fruitDiggingSolver) return

        val message = event.cleanMessage
        if (startPattern.matches(message)) {
            start()
            return
        }

        if (!engine.isActive()) return
        minesPattern.matchMatcher(message) {
            val pos = lastClickedPos ?: return@matchMatcher
            val amount = group("amount").toInt()
            if (engine.handleMinesDowse(pos, amount)) {
                debugOnce(
                    "mines:${pos.shortString()}:$amount:${engine.rounds()}",
                    "§6Fruit Digging debug: §cMines clue §7at §f${pos.shortString()} §7= §e$amount",
                )
            }
            return
        }

        treasurePattern.matchMatcher(message) {
            val pos = lastClickedPos ?: return@matchMatcher
            val kind = kindFromName(group("fruit"))?.takeIf { it.fruit } ?: return@matchMatcher
            if (engine.handleTreasureDowse(pos, kind)) {
                debugOnce(
                    "treasure:${pos.shortString()}:${kind.name}:${engine.rounds()}",
                    "§6Fruit Digging debug: §6Treasure clue §7at §f${pos.shortString()} §7= §a${kind.displayName}",
                )
            }
            return
        }

        noFruitsNearbyPattern.matchMatcher(message) {
            val pos = lastClickedPos ?: return@matchMatcher
            val mode = Mode.valueOf(group("mode"))
            val changed = when (mode) {
                Mode.ANCHOR -> engine.handleAnchorDowse(pos, null)
                Mode.TREASURE -> engine.handleTreasureDowse(pos, null)
                Mode.MINES -> false
            }
            if (changed) {
                debugOnce(
                    "no-fruits:${pos.shortString()}:${mode.name}:${engine.rounds()}",
                    "§6Fruit Digging debug: §c${mode.label} clue §7at §f${pos.shortString()} §7= §eno fruits",
                )
            }
        }
    }

    @HandleEvent
    fun onBlockClick(event: BlockClickEvent) {
        if (!isEnabled() || !engine.isActive()) return
        if (event.clickType != InteractClickType.LEFT_CLICK) return
        if (!event.itemInHand.isCarnivalShovel()) return

        val pos = boardPos(event.position) ?: return
        if (!pos.isDiggable()) return

        prunePendingDigs()
        pendingDigs[pos] = SimpleTimeMark.now()
        lastClickedPos = pos
    }

    @HandleEvent(ServerBlockChangeEvent::class)
    fun onBlockChange(event: ServerBlockChangeEvent) {
        if (!isEnabled() || !engine.isActive()) return
        val pos = boardPos(event.location) ?: return

        val becameTnt = event.newState.`is`(Blocks.TNT) || event.new == "tnt"
        val leftSand = event.oldState.`is`(Blocks.SAND) && !event.newState.`is`(Blocks.SAND)

        if (pendingDigs.remove(pos) != null) {
            if (engine.confirmDig(pos)) {
                lastConfirmedDigTime = SimpleTimeMark.now()
                hideRecommendationFor(300.milliseconds)
                debugOnce(
                    "dig:${pos.shortString()}:${engine.rounds()}",
                    "§6Fruit Digging debug: §aHand dig confirmed §7at §f${pos.shortString()} " +
                        "§7round §e${engine.rounds()}§7/§e${FruitDiggingSolverEngine.MAX_ROUNDS} " +
                        "§7(block ${event.old} -> ${event.new})",
                )
            }
            if (becameTnt) markBomb(pos, "dug bomb ${event.old} -> ${event.new}")
            return
        }

        // Chain exposion from the bomb
        if (becameTnt) {
            markBomb(pos, "block ${event.old} -> ${event.new}")
            return
        }
        if (leftSand && event.newState.`is`(Blocks.SANDSTONE)) {
            if (engine.markDestroyed(pos)) {
                debugOnce(
                    "destroyed:${pos.shortString()}:${engine.rounds()}",
                    "§6Fruit Digging debug: §7blast destroyed fruit candidate at §f${pos.shortString()} " +
                        "§7(block ${event.old} -> ${event.new})",
                )
            }
        }
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onEntityEnterWorld(event: EntityEnterWorldEvent<Entity>) {
        if (!isEnabled() || !engine.isActive()) return
        analyzeEntity(event.entity)
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onDataWatcherUpdate(event: DataWatcherUpdatedEvent<Entity>) {
        if (!isEnabled() || !engine.isActive()) return
        analyzeEntity(event.entity)
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onEntityEquipmentChange(event: EntityEquipmentChangeEvent<ArmorStand>) {
        if (!isEnabled() || !engine.isActive()) return
        if (!event.isHead) return
        analyzeArmorStand(event.entity)
    }

    @HandleEvent(onlyOnSkyblock = true)
    fun onEntityNameUpdate(event: EntityCustomNameUpdateEvent<ArmorStand>) {
        if (!isEnabled() || !engine.isActive()) return

        val name = event.newName?.removeColor() ?: return
        if (name.isBlank()) return

        revealFruitPattern.matchMatcher(name) {
            val kind = kindFromName(group("name")) ?: return@matchMatcher
            val pos = boardPos(event.entity) ?: lastClickedPos ?: return@matchMatcher
            resolveResult(pos, kind, parseShownValue(name), "armor stand name=$name")
        }
    }

    @HandleEvent
    fun onTick(event: SkyHanniTickEvent) {
        if (!isEnabled()) return
        if (!engine.isActive()) {
            display = emptyList()
            return
        }
        if (!event.isMod(2)) return

        syncBoardBlocks()
        scanBoardEntities()
        engine.recomputeIfDirty()
        updateDisplay()
    }

    @HandleEvent
    fun onRenderWorld(event: SkyHanniRenderWorldEvent) {
        if (!isEnabled() || !engine.isActive()) return
        if (recommendationHiddenUntil.isInFuture()) return
        val recommendation = stableRecommendation ?: return
        if (!recommendation.pos().isDiggable()) return
        val pos = recommendation.pos().toLorenzVec()

        event.drawWaypointFilled(pos, Color(40, 220, 80), seeThroughBlocks = true, minimumAlpha = 0.35f)
        event.drawDynamicText(
            pos.add(y = 1.35),
            "§aDig §cHERE §7with §8(§c${recommendation.mode().label}§8) §7${recommendation.bombProbability().formatPercentage()} bomb",
            1.35,
            seeThroughBlocks = true,
        )
    }

    @HandleEvent(GuiRenderEvent.GuiOverlayRenderEvent::class)
    fun onGuiRenderOverlay() {
        if (!isEnabled() || !engine.isActive() || display.isEmpty()) return
        config.fruitDiggingPosition.renderRenderables(display, posLabel = "Fruit Digging Solver")
    }

    @HandleEvent
    fun onWorldChange() {
        engine.reset()
        display = emptyList()
        pendingDigs.clear()
        lastClickedPos = null
        lastConfirmedDigTime = SimpleTimeMark.farPast()
        stableRecommendation = null
        stableRound = -1
        recommendationHiddenUntil = SimpleTimeMark.farPast()
        debugged.clear()
    }

    private fun start() {
        engine.start()
        display = emptyList()
        pendingDigs.clear()
        lastClickedPos = null
        lastConfirmedDigTime = SimpleTimeMark.farPast()
        stableRecommendation = null
        stableRound = -1
        recommendationHiddenUntil = SimpleTimeMark.farPast()
        debugged.clear()
        debugOnce(
            "start",
            "§6Fruit Digging solver started. §7Mine fruit/bomb tiles once and send the unknown fruit debug lines for texture mapping.",
        )
    }

    private fun scanBoardEntities() {
        EntityUtils.getEntitiesInBoundingBox<ArmorStand>(boardBox).forEach(::analyzeArmorStand)
        EntityUtils.getEntitiesInBoundingBox<ItemEntity>(boardBox).forEach(::analyzeItemEntity)
        EntityUtils.getEntitiesInBoundingBox<Entity>(boardBox) { it.isTntLike() }.forEach(::analyzeTnt)
    }

    private fun syncBoardBlocks() {
        for (x in FruitDiggingSolverEngine.MIN_X..FruitDiggingSolverEngine.MAX_X) {
            for (z in FruitDiggingSolverEngine.MIN_Z..FruitDiggingSolverEngine.MAX_Z) {
                val pos = BoardPos(x, FruitDiggingSolverEngine.BOARD_Y, z)
                if (!pos.isDiggable()) {
                    engine.markNotDiggable(pos)
                }
            }
        }
    }

    private fun analyzeEntity(entity: Entity) {
        when {
            entity is ArmorStand -> analyzeArmorStand(entity)
            entity is ItemEntity -> analyzeItemEntity(entity)
            entity.isTntLike() -> analyzeTnt(entity)
        }
    }

    private fun analyzeArmorStand(entity: ArmorStand) {
        val pos = boardPos(entity) ?: return
        val head = entity.getStandHelmet() ?: return
        if (head.isEmpty) return

        val texture = entity.getWornSkullTexture() ?: head.getSkullTexture()
        val knownKind = texture?.let(::kindFromSkullTexture)
            ?: kindFromText(
                head.hoverName.formattedTextCompatLeadingWhiteLessResets(),
                entity.name.formattedTextCompatLessResets(),
            )

        if (knownKind != null) {
            val shown = parseShownValue(head.hoverName.formattedTextCompatLeadingWhiteLessResets())
                ?: parseShownValue(entity.name.formattedTextCompatLessResets())
            resolveResult(pos, knownKind, shown, "armor stand texture=${textureDebugId(texture)}")
            return
        }

        if (texture == null && !head.`is`(Items.PLAYER_HEAD)) return
        val changed = engine.markFruitHint(pos)
        if (changed || texture != null) {
            debugUnknownFruit(pos, entity, head, texture)
        }
    }

    private fun analyzeItemEntity(entity: ItemEntity) {
        val pos = boardPos(entity) ?: lastClickedPos ?: return
        val itemStack = entity.item
        if (itemStack.isEmpty || itemStack.item == Items.AIR) return

        val texture = itemStack.getSkullTexture() ?: return
        val knownKind = kindFromSkullTexture(texture) ?: kindFromText(
            itemStack.hoverName.formattedTextCompatLeadingWhiteLessResets(),
            entity.name.formattedTextCompatLessResets(),
        )

        if (knownKind != null) {
            val shown = parseShownValue(itemStack.hoverName.formattedTextCompatLeadingWhiteLessResets())
                ?: parseShownValue(entity.name.formattedTextCompatLessResets())
            resolveResult(pos, knownKind, shown, "item texture=${textureDebugId(texture)}")
            return
        }

        debugUnknownItemFruit(pos, entity, itemStack, texture)
    }

    private fun analyzeTnt(entity: Entity) {
        val pos = boardPos(entity) ?: lastClickedPos ?: return
        markBomb(pos, "entity type=${entity.type} name=${entity.name.formattedTextCompatLessResets()}")
    }

    private fun resolveResult(pos: BoardPos, kind: Kind, shownValue: Int?, source: String) {
        when {
            kind == Kind.BOMB -> markBomb(pos, source)
            kind.fruit -> resolveFruit(pos, kind, shownValue, source)
            else -> if (engine.setKind(pos, kind)) { // rum
                debugOnce(
                    "known-other:${pos.shortString()}:${kind.name}:${engine.rounds()}",
                    "§6Fruit Digging debug: §a${kind.displayName} §7at §f${pos.shortString()} §7from §f$source",
                )
            }
        }
    }

    private fun resolveFruit(pos: BoardPos, kind: Kind, shownValue: Int?, source: String) {
        val wasDug = engine.isDug(pos)
        val changed = engine.setKind(pos, kind)

        // Only record the kind and feed the ordering clue into the solver.
        val clicked = lastClickedPos
        val isReveal = clicked != null && clicked != pos && !wasDug
        val addedClue = changed && isReveal && clicked!!.isAdjacentTo(pos) &&
            engine.handleAnchorDowse(clicked, kind)

        val observedMultiplier = shownValue != null && isReveal && engine.observeShownValue(kind, shownValue)

        if (changed || addedClue || observedMultiplier) {
            val state = if (wasDug) "dug" else "revealed"
            val valueText = shownValue?.let { " §7value=§e$it" } ?: ""
            debugOnce(
                "known-fruit:${pos.shortString()}:${kind.name}:${engine.rounds()}",
                "§6Fruit Digging debug: §a${kind.displayName} §7$state at §f${pos.shortString()}$valueText §7from §f$source",
            )
        }
    }

    private fun parseShownValue(text: String): Int? =
        shownValuePattern.findMatcher(text.removeColor()) { group("value").toIntOrNull() }

    private fun markBomb(pos: BoardPos, source: String) {
        val playerDug = pendingDigs.containsKey(pos) || engine.isDug(pos)
        val changed = if (playerDug) engine.setKind(pos, Kind.BOMB) else engine.markDestroyed(pos)
        if (changed) {
            hideRecommendationFor(1.seconds)
            debugOnce(
                "bomb:${pos.shortString()}:${engine.rounds()}",
                "§6Fruit Digging debug: §cBomb/TNT §7at §f${pos.shortString()} §7from §f$source §7(playerDug=$playerDug)",
            )
        }
    }

    private fun debugUnknownFruit(pos: BoardPos, entity: ArmorStand, head: SafeItemStack, texture: String?) {
        val textureId = textureDebugId(texture)
        val headName = head.hoverName.formattedTextCompatLeadingWhiteLessResets()
        val entityName = entity.name.formattedTextCompatLessResets()
        val key = "unknown-fruit:${pos.shortString()}:$textureId:$headName"

        debugOnce(
            key,
            "§6Fruit Digging debug: §eUnknown fruit §7at §f${pos.shortString()} " +
                "§7texture=§b$textureId §7head=§f$headName §7entity=§f$entityName",
            "§6Fruit Digging texture $textureId: §f${textureDebugPayload(texture)}",
        )
    }

    private fun debugUnknownItemFruit(pos: BoardPos, entity: ItemEntity, itemStack: SafeItemStack, texture: String) {
        val textureId = textureDebugId(texture)
        val itemName = itemStack.hoverName.formattedTextCompatLeadingWhiteLessResets()
        val entityName = entity.name.formattedTextCompatLessResets()
        val key = "unknown-item-fruit:${pos.shortString()}:$textureId:$itemName"

        debugOnce(
            key,
            "§6Fruit Digging debug: §eUnknown item fruit §7at §f${pos.shortString()} " +
                "§7texture=§b$textureId §7item=§f$itemName §7entity=§f$entityName",
            "§6Fruit Digging texture $textureId: §f${textureDebugPayload(texture)}",
        )
    }

    private fun updateStableRecommendation() {
        val round = engine.rounds()
        if (round != stableRound || stableRecommendation?.pos()?.isDiggable() == false) {
            stableRecommendation = null
        }
        if (stableRecommendation != null) return

        if (engine.isComputing() || lastConfirmedDigTime.passedSince() < recommendationSettleDelay) return
        val candidate = engine.recommendation()?.takeIf { it.pos().isDiggable() } ?: return
        stableRecommendation = candidate
        stableRound = round
    }

    private fun updateDisplay() {
        updateStableRecommendation()
        val recommendation = stableRecommendation
        val waitingForBlockUpdates = recommendationHiddenUntil.isInFuture()
        display = buildList {
            addString("§6§lFruit Digging Solver")
            addString("§7Rounds: §e${engine.rounds()}§7/§e${FruitDiggingSolverEngine.MAX_ROUNDS}")

            when {
                waitingForBlockUpdates -> addString("Waiting for block updates...")

                recommendation != null -> {
                    addString("§aDig: §f${recommendation.pos().shortString()}")
                    addString("§7Use: §aCarnival Shovel §8(§c${recommendation.mode().label}§8)")
                    addString("§cBomb: §e${recommendation.bombProbability().formatPercentage()} §7Samples: §e${recommendation.samples()}")
                    addString("§7Dowsing: §c${recommendation.mode().label}")
                }

                engine.isComputing() -> addString("§eThinking...")
                else -> addString("§7Waiting for a fruit/TNT result...")
            }
        }
    }

    private fun SafeItemStack?.isCarnivalShovel(): Boolean {
        this ?: return false
        if (isEmpty) return false
        if (getItemId() == "CARNIVAL_SHOVEL") return true
        if (itemType != Items.IRON_SHOVEL) return false

        val name = hoverName.formattedTextCompatLeadingWhiteLessResets()
        if (name.removeColor() != "Carnival Shovel") return false
        return getLore().any { it.removeColor().contains("Fruit Digging") }
    }

    private fun boardPos(location: LorenzVec): BoardPos? {
        val rounded = location.roundToBlock()
        return FruitDiggingSolverEngine.boardPos(rounded.x.toInt(), rounded.y.toInt(), rounded.z.toInt())
    }

    private fun boardPos(entity: Entity): BoardPos? {
        val location = entity.getLorenzVec()
        if (location.y < FruitDiggingSolverEngine.BOARD_Y || location.y > FruitDiggingSolverEngine.BOARD_Y + 4) return null
        return FruitDiggingSolverEngine.boardPos(
            floor(location.x).toInt(),
            FruitDiggingSolverEngine.BOARD_Y,
            floor(location.z).toInt(),
        )
    }

    private fun hideRecommendationFor(duration: Duration) {
        val hiddenUntil = SimpleTimeMark.now() + duration
        if (recommendationHiddenUntil < hiddenUntil) {
            recommendationHiddenUntil = hiddenUntil
        }
        updateDisplay()
    }

    private fun prunePendingDigs() {
        pendingDigs.entries.removeIf { it.value.passedSince() >= pendingDigTimeout }
    }

    private fun kindFromName(name: String): Kind? {
        val normalized = name.removeColor().replace(" ", "").lowercase(Locale.US)
        return Kind.values().firstOrNull {
            it.displayName.replace(" ", "").lowercase(Locale.US) == normalized
        }
    }

    private fun kindFromText(vararg textParts: String): Kind? {
        val text = textParts.joinToString(" ").removeColor().replace(" ", "").lowercase(Locale.US)
        return Kind.values().firstOrNull {
            text.contains(it.displayName.replace(" ", "").lowercase(Locale.US))
        }
    }

    private fun kindFromSkullTexture(texture: String): Kind? {
        val decoded = decodedTexture(texture) ?: texture
        return knownFruitTextureIds.entries.firstOrNull { decoded.contains(it.key, ignoreCase = true) }?.value
    }

    private fun textureId(texture: String): String? {
        val decoded = decodedTexture(texture) ?: texture
        return knownFruitTextureIds.keys.firstOrNull { decoded.contains(it, ignoreCase = true) }
            ?: textureIdPattern.findMatcher(decoded.lowercase(Locale.US)) { group("id") }
    }

    private fun textureDebugId(texture: String?): String = texture?.let { textureId(it) ?: "unknown" } ?: "none"

    private fun textureDebugPayload(texture: String?): String = texture?.let { decodedTexture(it) ?: it } ?: "null"

    private fun decodedTexture(texture: String): String? = runCatching { StringUtils.decodeBase64(texture) }.getOrNull()

    private fun BoardPos.isAdjacentTo(other: BoardPos): Boolean {
        val dx = abs(x() - other.x())
        val dz = abs(z() - other.z())
        return y() == other.y() && dx <= 1 && dz <= 1 && (dx != 0 || dz != 0)
    }

    private fun Entity.isTntLike(): Boolean {
        val typeName = type.toString().lowercase(Locale.US)
        val displayName = name.string.lowercase(Locale.US)
        return "tnt" in typeName || "tnt" in displayName
    }

    private fun BoardPos.toLorenzVec() = LorenzVec(x().toDouble(), y().toDouble(), z().toDouble())

    private fun BoardPos.isDiggable() = toLorenzVec().getBlockAt() == Blocks.SAND

    private fun debugOnce(key: String, vararg messages: String) {
        if (!config.fruitDiggingDebug) return
        if (!debugged.add(key)) return
        for (message in messages) {
            ChatUtils.chat(message)
            ChatUtils.consoleLog(message.removeColor())
        }
    }

    private fun isEnabled() = config.fruitDiggingSolver && (CarnivalAPI.inCarnivalArea || engine.isActive())
}
