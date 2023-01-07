package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.Spy
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.min
import kotlin.random.Random

class CityEspionageManager : IsPartOfGameInfoSerialization{
    @Transient
    lateinit var cityInfo: CityInfo

    // For City-State Elections, null for major civs
    var turnsUntilNextElection: Int? = null
    var votes: Counter<String>? = null

    fun clone(): CityEspionageManager {
        val toReturn = CityEspionageManager()
        toReturn.turnsUntilNextElection = turnsUntilNextElection
        if ( votes == null)
            toReturn.votes = null
        else {
            toReturn.votes = Counter()
            toReturn.votes!!.putAll(votes!!)
        }
        return toReturn
    }

    private fun cityStateInit() {
        turnsUntilNextElection = 15
        votes = Counter()
    }

    fun cityStateEndTurn() {
        if (votes == null) cityStateInit()
        turnsUntilNextElection = turnsUntilNextElection!! - 1
        if (turnsUntilNextElection == 0) {
            for (abstainingCiv in votes!!.keys.filter { civ -> civ !in getSpies().map { it.civInfo.civName } }) {
                // When election, if a spy from 'civ' is not in this city-state, then 'civ' gets no votes
                votes!!.remove(abstainingCiv)
            }

            if (votes!!.sumValues() == 0) {
                // No votes -> return, as Random.nextInt requires positive integer
                cityStateResetElection()
                return
            }

            // Randomly select votes
            val r = Random.nextInt(votes!!.sumValues())
            var start = 0
            var end: Int
            var selected = ""
            for (entry in votes!!.entries) {
                end = start + entry.value - 1
                if ( r in start .. end) {
                    selected = entry.key
                    break
                }
                start += entry.value
            }
            val winner = cityInfo.civInfo.gameInfo.getCivilization(selected)

            cityInfo.civInfo.getDiplomacyManager(winner).addInfluence(20.0f)
            val winnerSpy = getSpies().firstOrNull { it.civInfo == winner }!!
            winner.addNotification(
                "[${winnerSpy.getRank()}] [${winnerSpy.name}] successfully rigged the local elections in [${cityInfo.name}], increasing your influence and reducing the influence of other major civs.",
                cityInfo.location, NotificationIcon.Spy)

            for (civ in cityInfo.civInfo.gameInfo.getAliveMajorCivs().filter { it != winner }) {
                cityInfo.civInfo.getDiplomacyManager(civ).addInfluence(-5.0f)
            }
            for (spy in getSpies().filter { it.civInfo != winner }) {
                spy.civInfo.addNotification(
                    "[${spy.getRank()}] [${spy.name}] failed to rig the local elections in [${cityInfo.name}]. [${winner.civName}] succeeded in rigging them and have gained influence there.",
                    cityInfo.location, NotificationIcon.Spy)
            }

            cityStateResetElection()
        } else if (getSpies().isEmpty()) {
            cityStateResetElection()
        } else {
            for (spy in getSpies()) {
                votes!!.add(spy.civInfo.civName, spy.level * spy.level)
            }
        }
    }

    private fun cityStateResetElection() {
        turnsUntilNextElection = 15
        votes!!.clear()
    }

    fun setTransients(cityInfo: CityInfo) {
        this.cityInfo = cityInfo
    }

    fun getDefenceModifier(): Int {
        var modifier = 100
        cityInfo.getMatchingUniques(UniqueType.ReducesSpyEffectiveness).forEach {
            if(cityInfo.matchesFilter(it.params[1]))
                modifier -= it.params[0].toFloat().toInt()
        }
        return modifier
    }

    fun onTechSteal(): Boolean {
        var modifier = 100
        cityInfo.getMatchingUniques(UniqueType.IncreasesCounterspyCatchingChance).forEach {
            if (cityInfo.matchesFilter(it.params[1]))
                modifier += it.params[0].toFloat().toInt()
        }

        // 3 params for dealWithSpy
        val k = min(100, 33 * modifier / 100)
        val i = (100-k) / 2
        val j = 100 - k - i

        return if(hasSpyOf(cityInfo.civInfo)) {
            val spyLevelModifier = 10 * (getSpies().first { it.civInfo == cityInfo.civInfo }.level - 1)
            dealWithSpy(0, i-spyLevelModifier, j, k+spyLevelModifier)
        } else {
            dealWithSpy(i, j, k, 0)
        }
    }

    /** The 4 params are the probability of the event.
     * @return true if spy died. false if spy is alive.
     */
    private fun dealWithSpy(nothing: Int, notice: Int, recognize: Int, kill: Int): Boolean {
        val spies = getSpies()
        val enemySpy = spies.first { it.civInfo != cityInfo.civInfo }
        val ownSpy = spies.firstOrNull { it.civInfo == cityInfo.civInfo }
        return when(Random.nextInt(100)) {
            in 0 until nothing -> false
            in nothing until nothing + notice -> {
                cityInfo.civInfo.addNotification("An unidentified spy stole technological secrets from [${cityInfo.name}]!",
                    cityInfo.location, NotificationIcon.Spy)
                false
            }
            in nothing + notice until nothing + notice + recognize -> {
                cityInfo.civInfo.addNotification("A spy from [${enemySpy.civInfo.civName}] stole technological secrets from [${cityInfo.name}]!",
                    cityInfo.location, NotificationIcon.Spy)
                false
            }
            in 100 - kill until 100 -> {
                cityInfo.civInfo.addNotification(
                    "Your spy [${ownSpy!!.getRank()}] [${ownSpy.name}] killed a spy from [${enemySpy.civInfo.civName}] in [${cityInfo.name}] while they were trying to steal a technology!",
                    cityInfo.location, NotificationIcon.Spy)
                ownSpy.levelUp()
                enemySpy.civInfo.addNotification("Your spy [${enemySpy.getRank()}] [${enemySpy.name}] was killed by a counterspy while your spy was trying to steal a technology in [${cityInfo.name}]!")
                enemySpy.kill()
                true
            }
            else -> false
        }
    }

    fun getSpies(): MutableList<Spy> {
        val list = mutableListOf<Spy>()
        for (civ in cityInfo.civInfo.gameInfo.civilizations) {
            civ.espionageManager.spyList.firstOrNull { it.location == cityInfo.id }?.let { list.add(it) }
        }
        return list;
    }

    fun hasSpyOf(civInfo: CivilizationInfo): Boolean {
        return civInfo.espionageManager.spyList.any { it.location == cityInfo.id }
    }

}
