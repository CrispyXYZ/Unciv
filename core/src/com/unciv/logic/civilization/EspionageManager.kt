package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.CityInfo

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
        return toReturn
    }

    fun setTransients(civInfo: CivilizationInfo) {
        this.civInfo = civInfo
    }

    fun endTurn() {
        --timeTillActionFinish
        if (timeTillActionFinish > 0) return

        when (action) {
            SpyAction.Moving -> {
                action = SpyAction.EstablishNetwork
                timeTillActionFinish = 3 // Dependent on cultural familiarity level if that is ever implemented
            }
            SpyAction.EstablishNetwork -> {
                val location = getLocation()!! // This should be impossible to reach as going to the hideout sets your action to None.
                action =
                    if (location.civInfo.isCityState()) {
                        SpyAction.RiggingElections
                    } else if (location.civInfo == civInfo) {
                        SpyAction.CounterIntelligence
                    } else {
                        attemptToStealTech(getStealableTechs(location.civInfo), location)
                    }
            }
            SpyAction.StealingTech -> {
                val location = getLocation()!!
                getStealableTechs(location.civInfo).randomOrNull()?.let { civInfo.tech.addTechnology(it) }
                action = attemptToStealTech(getStealableTechs(location.civInfo), location)
            }
            SpyAction.None -> {
                ++timeTillActionFinish
                val location = getLocation() ?: return
                if(location.civInfo != civInfo && !location.civInfo.isCityState())
                    action = attemptToStealTech(getStealableTechs(location.civInfo), location, false)
            }
            else -> {
                ++timeTillActionFinish // Not implemented yet, so don't do anything
            }
        }
    }

    private fun attemptToStealTech(
        techsStealable: HashSet<String>,
        location: CityInfo,
        notifyIfNothingToSteal: Boolean = true
    ): SpyAction {
        return if (techsStealable.isEmpty()) {
            if(notifyIfNothingToSteal) civInfo.addNotification(
                "Spy [$name] cannot steal from [${location.civInfo.civName}] because we've completely eclipsed them in research.",
                location.location, NotificationIcon.Spy)
            SpyAction.None
        } else {
            val stealingSpeed = location.cityStats.currentCityStats.science.toInt() * 5
            timeTillActionFinish = techsStealable.maxOf { civInfo.tech.costOfTech(it) } / stealingSpeed
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

    fun isSetUp() = action !in listOf(SpyAction.Moving, SpyAction.None, SpyAction.EstablishNetwork)

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

    fun getLocation(): CityInfo? {
        return civInfo.gameInfo.getCities().firstOrNull { it.id == location }
    }

    fun getLocationName(): String {
        return getLocation()?.name ?: Constants.spyHideout
    }
}

class EspionageManager : IsPartOfGameInfoSerialization {

    var spyCount = 0
    var spyList = mutableListOf<Spy>()
    var erasSpyEarnedFor = mutableListOf<String>()

    @Transient
    lateinit var civInfo: CivilizationInfo

    fun clone(): EspionageManager {
        val toReturn = EspionageManager()
        toReturn.spyCount = spyCount
        toReturn.spyList.addAll(spyList.map { it.clone() })
        toReturn.erasSpyEarnedFor.addAll(erasSpyEarnedFor)
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
}
