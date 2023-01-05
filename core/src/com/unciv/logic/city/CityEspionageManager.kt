package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.Spy
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.min
import kotlin.random.Random

class CityEspionageManager : IsPartOfGameInfoSerialization{
    @Transient
    lateinit var cityInfo: CityInfo

    fun clone(): CityEspionageManager {
        return CityEspionageManager()
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
                    "Your spy [${ownSpy!!.getRank()}] [${ownSpy!!.name}] killed a spy from [${enemySpy.civInfo.civName}] in [${cityInfo.name}] while they were trying to steal a technology!",
                    cityInfo.location, NotificationIcon.Spy)
                ownSpy!!.levelUp()
                enemySpy.civInfo.addNotification("Your spy [${enemySpy.getRank()}] [${enemySpy.name}] was killed by a counterspy while your spy was trying to steal a technology in [${cityInfo.name}]!")
                enemySpy.kill()
                true
            }
            else -> false
        }
    }

    private fun getSpies(): MutableList<Spy> {
        val list = mutableListOf<Spy>()
        cityInfo.civInfo.gameInfo.civilizations.forEach { civ ->
            civ.espionageManager.spyList.firstOrNull { it.location == cityInfo.id }?.let { list.add(it) }
        }
        return list;
    }

    fun hasSpyOf(civInfo: CivilizationInfo): Boolean {
        return civInfo.espionageManager.spyList.any { it.location == cityInfo.id }
    }

}
