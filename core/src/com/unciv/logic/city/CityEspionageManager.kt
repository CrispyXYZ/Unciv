package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.unique.UniqueType

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

    fun hasSpyOf(civInfo: CivilizationInfo): Boolean {
        return civInfo.espionageManager.spyList.any { it.location == cityInfo.id }
    }

}
