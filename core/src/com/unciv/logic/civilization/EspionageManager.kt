package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.CityInfo
import kotlin.math.ceil
import kotlin.math.max

enum class SpyAction(val stringName: String) {
    None("None"),
    Moving("Moving"),
    EstablishNetwork("Establishing Network"),
    StealingTech("Stealing Tech"),
    RiggingElections("Rigging Elections"),
    CounterIntelligence("Conducting Counter-intelligence")
}


class Spy() : IsPartOfGameInfoSerialization {
    // `location == null` means that the spy is in its hideout
    var location: String? = null
    lateinit var name: String
    var timeTillActionFinish = 0
    var action = SpyAction.None
    var level = 1

    @Transient
    lateinit var civInfo: CivilizationInfo

    constructor(name: String) : this() {
        this.name = name
    }

    fun clone(): Spy {
        val toReturn = Spy(name)
        toReturn.location = location
        toReturn.timeTillActionFinish = timeTillActionFinish
        toReturn.action = action
        toReturn.level = level
        return toReturn
    }

    fun setTransients(civInfo: CivilizationInfo) {
        this.civInfo = civInfo
    }

    fun kill() {
        civInfo.espionageManager.killSpy(this)
    }

    fun endTurn() {
        --timeTillActionFinish
        if (timeTillActionFinish > 0) return

        when (action) {
            SpyAction.Moving -> {
                action = if (getLocation()!!.civInfo == civInfo) {
                    timeTillActionFinish = 1
                    SpyAction.CounterIntelligence
                } else {
                    timeTillActionFinish = 3 // Dependent on cultural familiarity level if that is ever implemented
                    SpyAction.EstablishNetwork
                }
            }
            SpyAction.EstablishNetwork -> {
                val location = getLocation()!! // This should be impossible to reach as going to the hideout sets your action to None.
                action =
                    if (location.civInfo.isCityState()) {
                        timeTillActionFinish = getLocation()!!.espionage.turnsUntilNextElection!! - 1
                        SpyAction.RiggingElections
                    } else {
                        attemptToStealTech(getStealableTechs(location.civInfo), location)
                    }
            }
            SpyAction.RiggingElections -> {
                timeTillActionFinish = getLocation()!!.espionage.turnsUntilNextElection!! - 1
            }
            SpyAction.CounterIntelligence -> {
                timeTillActionFinish = 1
            }
            SpyAction.StealingTech -> {
                val location = getLocation()!!
                if(location.espionage.onTechSteal()) return  // Spy died
                val tech = getStealableTechs(location.civInfo).randomOrNull()
                if (tech != null) {
                    levelUp()
                    civInfo.tech.addTechnology(tech)
                }
                action = attemptToStealTech(getStealableTechs(location.civInfo), location)
            }
            SpyAction.None -> {
                ++timeTillActionFinish
                val location = getLocation() ?: return
                if(location.civInfo != civInfo && !location.civInfo.isCityState())
                    action = attemptToStealTech(getStealableTechs(location.civInfo), location, false)
            }
        }
    }

    fun levelUp() {
        if (level < 3) {
            ++level
            civInfo.addNotification("[$name] has been promoted!", NotificationIcon.Spy)
        }
    }

    private fun attemptToStealTech(
        techsStealable: HashSet<String>,
        location: CityInfo,
        notifyIfNothingToSteal: Boolean = true): SpyAction {
        return if (techsStealable.isEmpty()) {
            if(notifyIfNothingToSteal) civInfo.addNotification(
                "[${getRank()}] [$name] cannot steal from [${location.civInfo.civName}] because we have completely eclipsed them in research!",
                location.location, NotificationIcon.Spy)
            SpyAction.None
        } else {
            val defenceModifier = location.espionage.getDefenceModifier()
            val spyModifier: Double = 0.75 + 0.25*level
            val sciencePoints = location.cityStats.currentCityStats.science
            val effectiveness = max(1.0, sciencePoints * defenceModifier * spyModifier)
            timeTillActionFinish = ceil(techsStealable.maxOf { civInfo.tech.costOfTech(it) } * 125 / effectiveness).toInt()
            SpyAction.StealingTech
        }
    }

    fun moveTo(cityInfo: CityInfo?) {
        location = cityInfo?.id
        if (cityInfo == null) { // Moving to spy hideout
            action = SpyAction.None
            timeTillActionFinish = 0
            return
        }
        action = SpyAction.Moving
        timeTillActionFinish = 1
    }

    fun isSetUp() = action !in listOf(SpyAction.Moving, SpyAction.EstablishNetwork)

    private fun getAvailableTechs(): HashSet<String> {
        return civInfo.gameInfo.ruleSet.technologies.keys
            .filter { civInfo.tech.canBeResearched(it) }.toHashSet()
    }

    private fun getStealableTechs(otherCiv: CivilizationInfo): HashSet<String> {
        return hashSetOf<String>().apply {
            addAll(getAvailableTechs())
            removeIf { !otherCiv.tech.techsResearched.contains(it) }
        }
    }

    fun getRank(): String = when(level) {
        1 -> "Recruit"
        2 -> "Agent"
        3 -> "Special Agent"
        else -> level.toString() // Unreachable because level is always < 4
    }

    fun getLocation(): CityInfo? {
        return civInfo.gameInfo.getCities().firstOrNull { it.id == location }
    }

    fun getLocationName(): String {
        return getLocation()?.name ?: Constants.spyHideout
    }
}

class EspionageManager : IsPartOfGameInfoSerialization {

    var spyCount = 0
    var replacementCountDown = 5;
    var spyList = mutableListOf<Spy>()
    var erasSpyEarnedFor = mutableListOf<String>()

    @Transient
    lateinit var civInfo: CivilizationInfo

    fun clone(): EspionageManager {
        val toReturn = EspionageManager()
        toReturn.spyCount = spyCount
        toReturn.spyList.addAll(spyList.map { it.clone() })
        toReturn.erasSpyEarnedFor.addAll(erasSpyEarnedFor)
        toReturn.replacementCountDown = replacementCountDown
        return toReturn
    }

    fun setTransients(civInfo: CivilizationInfo) {
        this.civInfo = civInfo
        for (spy in spyList) {
            spy.setTransients(civInfo)
        }
    }

    fun endTurn() {
        for (spy in spyList)
            spy.endTurn()
        if (spyList.size != spyCount) recruitReplacementSpy() // Recruit new spy if death
    }

    private fun recruitReplacementSpy() {
        --replacementCountDown
        if (replacementCountDown <= 0) {
            val spyName = addSpy()
            civInfo.addNotification("We have recruited [$spyName] to replace a spy that was killed in action!", NotificationIcon.Spy)
            replacementCountDown = 5
            --spyCount
        }
    }

    private fun getSpyName(): String {
        val usedSpyNames = spyList.map { it.name }.toHashSet()
        val validSpyNames = civInfo.nation.spyNames.filter { it !in usedSpyNames }
        if (validSpyNames.isEmpty()) { return "Spy ${spyList.size+1}" } // +1 as non-programmers count from 1
        return validSpyNames.random()
    }

    fun addSpy(): String {
        val spyName = getSpyName()
        val newSpy = Spy(spyName)
        newSpy.setTransients(civInfo)
        spyList.add(newSpy)
        ++spyCount
        return spyName
    }

    fun killSpy(spy: Spy) {
        spy.moveTo(null)
        spyList.remove(spy)
    }
}
