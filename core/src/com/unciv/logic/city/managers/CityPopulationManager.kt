package com.unciv.logic.city.managers

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.Automation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.utils.extensions.toPercent
import com.unciv.ui.utils.extensions.withItem
import com.unciv.ui.utils.extensions.withoutItem
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

class CityPopulationManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var city: City

    var population = 1
        private set
    var foodStored = 0

    // In favor of this bad boy
    val specialistAllocations = Counter<String>()

    fun getNewSpecialists() = specialistAllocations //convertStatsToSpecialistHashmap(specialists)


    //region pure functions
    fun clone(): CityPopulationManager {
        val toReturn = CityPopulationManager()
        toReturn.specialistAllocations.add(specialistAllocations)
        toReturn.population = population
        toReturn.foodStored = foodStored
        return toReturn
    }

    fun getNumberOfSpecialists() = getNewSpecialists().values.sum()

    fun getFreePopulation(): Int {
        val workingPopulation = city.workedTiles.size
        return population - workingPopulation - getNumberOfSpecialists()
    }

    fun getFoodToNextPopulation(): Int {
        // civ v math, civilization.wikia
        var foodRequired = 15 + 6 * (population - 1) + floor((population - 1).toDouble().pow(1.8))

        foodRequired *= city.civ.gameInfo.speed.modifier

        if (city.civ.isCityState())
            foodRequired *= 1.5f
        if (!city.civ.isHuman())
            foodRequired *= city.civ.gameInfo.getDifficulty().aiCityGrowthModifier
        return foodRequired.toInt()
    }

    /** Take null to mean infinity. */
    fun getNumTurnsToStarvation(): Int? {
        if (!city.isStarving()) return null
        return foodStored / -city.foodForNextTurn() + 1
    }


    /** Take null to mean infinity. */
    fun getNumTurnsToNewPopulation(): Int? {
        if (!city.isGrowing()) return null
        val roundedFoodPerTurn = city.foodForNextTurn().toFloat()
        val remainingFood = getFoodToNextPopulation() - foodStored
        var turnsToGrowth = ceil(remainingFood / roundedFoodPerTurn).toInt()
        if (turnsToGrowth < 1) turnsToGrowth = 1
        return turnsToGrowth
    }


    //endregion

    /** Implements [UniqueParameterType.PopulationFilter][com.unciv.models.ruleset.unique.UniqueParameterType.PopulationFilter] */
    fun getPopulationFilterAmount(filter: String): Int {
        return when (filter) {
            "Specialists" -> getNumberOfSpecialists()
            "Population" -> population
            "Followers of the Majority Religion", "Followers of this Religion" -> city.religion.getFollowersOfMajorityReligion()
            "Unemployed" -> getFreePopulation()
            else -> 0
        }
    }


    fun nextTurn(food: Int) {
        foodStored += food
        if (food < 0)
            city.civ.addNotification("[${city.name}] is starving!",
                city.location, NotificationCategory.Cities, NotificationIcon.Growth, NotificationIcon.Death)
        if (foodStored < 0) {        // starvation!
            if (population > 1) addPopulation(-1)
            foodStored = 0
        }
        if (foodStored >= getFoodToNextPopulation()) {  // growth!
            foodStored -= getFoodToNextPopulation()
            var percentOfFoodCarriedOver =
                city.getMatchingUniques(UniqueType.CarryOverFood)
                    .filter { city.matchesFilter(it.params[1]) }
                    .sumOf { it.params[0].toInt() }
            // Try to avoid runaway food gain in mods, just in case
            if (percentOfFoodCarriedOver > 95) percentOfFoodCarriedOver = 95
            foodStored += (getFoodToNextPopulation() * percentOfFoodCarriedOver / 100f).toInt()
            addPopulation(1)
            city.updateCitizens = true
            city.civ.addNotification("[${city.name}] has grown!", city.location,
                NotificationCategory.Cities, NotificationIcon.Growth)
        }
    }

    fun addPopulation(count: Int) {
        val changedAmount =
            if (population + count < 0) -population
            else count
        population += changedAmount
        val freePopulation = getFreePopulation()
        if (freePopulation < 0) {
            unassignExtraPopulation()
        } else {
            autoAssignPopulation()
        }

        if (city.civ.gameInfo.isReligionEnabled())
            city.religion.updatePressureOnPopulationChange(changedAmount)
    }

    fun setPopulation(count: Int) {
        addPopulation(-population + count)
    }

    internal fun autoAssignPopulation() {
        city.cityStats.update()  // calculate current stats with current assignments
        val cityStats = city.cityStats.currentCityStats
        city.currentGPPBonus = city.getGreatPersonPercentageBonus()  // pre-calculate
        var specialistFoodBonus = 2f  // See CityStats.calcFoodEaten()
        for (unique in city.getMatchingUniques(UniqueType.FoodConsumptionBySpecialists))
            if (city.matchesFilter(unique.params[1]))
                specialistFoodBonus *= unique.params[0].toPercent()
        specialistFoodBonus = 2f - specialistFoodBonus

        val currentCiv = city.civ

        val tilesToEvaluate = city.getCenterTile().getTilesInDistance(3)
            .filter { it.getOwner() == currentCiv }.toList().asSequence()
        for (i in 1..getFreePopulation()) {
            //evaluate tiles
            val (bestTile, valueBestTile) = tilesToEvaluate
                    .filterNot { it.providesYield() }
                    .associateWith { Automation.rankTileForCityWork(it, city, cityStats) }
                    .maxByOrNull { it.value }
                    ?: object : Map.Entry<Tile?, Float> {
                        override val key: Tile? = null
                        override val value = 0f
                    }

            val bestJob: String? = if (city.manualSpecialists) null else getMaxSpecialists()
                    .filter { specialistAllocations[it.key]!! < it.value }
                    .map { it.key }
                    .maxByOrNull { Automation.rankSpecialist(it, city, cityStats) }

            var valueBestSpecialist = 0f
            if (bestJob != null) {
                valueBestSpecialist = Automation.rankSpecialist(bestJob, city, cityStats)
            }

            //assign population
            if (valueBestTile > valueBestSpecialist) {
                if (bestTile != null) {
                    city.workedTiles = city.workedTiles.withItem(bestTile.position)
                    cityStats[Stat.Food] += bestTile.stats.getTileStats(city, city.civ)[Stat.Food]
                }
            } else if (bestJob != null) {
                specialistAllocations.add(bestJob, 1)
                cityStats[Stat.Food] += specialistFoodBonus
            }
        }
        city.cityStats.update()
    }

    fun stopWorkingTile(position: Vector2){
        city.workedTiles = city.workedTiles.withoutItem(position)
        city.lockedTiles.remove(position)
    }

    fun unassignExtraPopulation() {
        for (tile in city.workedTiles.map { city.tileMap[it] }) {
            if (tile.getOwner() != city.civ || tile.getWorkingCity() != city
                    || tile.aerialDistanceTo(city.getCenterTile()) > 3)
                city.population.stopWorkingTile(tile.position)
        }

        // unassign specialists that cannot be (e.g. the city was captured and one of the specialist buildings was destroyed)
        val maxSpecialists = getMaxSpecialists()
        val specialistsHashmap = specialistAllocations
        for ((specialistName, amount) in specialistsHashmap)
            if (amount > maxSpecialists[specialistName]!!)
                specialistAllocations[specialistName] = maxSpecialists[specialistName]!!



        while (getFreePopulation() < 0) {
            //evaluate tiles
            val worstWorkedTile: Tile? = if (city.workedTiles.isEmpty()) null
            else {
                city.workedTiles.asSequence()
                        .map { city.tileMap[it] }
                        .minByOrNull {
                            Automation.rankTileForCityWork(it, city, city.cityStats.currentCityStats)
                            +(if (it.isLocked()) 10 else 0)
                        }!!
            }
            val valueWorstTile = if (worstWorkedTile == null) 0f
            else Automation.rankTileForCityWork(worstWorkedTile, city, city.cityStats.currentCityStats)

            //evaluate specialists
            val worstAutoJob: String? = if (city.manualSpecialists) null else specialistAllocations.keys
                    .minByOrNull { Automation.rankSpecialist(it, city, city.cityStats.currentCityStats) }
            var valueWorstSpecialist = 0f
            if (worstAutoJob != null)
                valueWorstSpecialist = Automation.rankSpecialist(worstAutoJob, city, city.cityStats.currentCityStats)


            // un-assign population
            when {
                worstAutoJob != null && worstWorkedTile != null -> {
                    // choose between removing a specialist and removing a tile
                    if (valueWorstTile < valueWorstSpecialist) {
                        stopWorkingTile(worstWorkedTile.position)
                    }
                    else
                        specialistAllocations.add(worstAutoJob, -1)
                }
                worstAutoJob != null -> specialistAllocations.add(worstAutoJob, -1)
                worstWorkedTile != null -> stopWorkingTile(worstWorkedTile.position)
                else -> {
                    // It happens when "cityInfo.manualSpecialists == true"
                    //  and population goes below the number of specialists, e.g. city is razing.
                    // Let's give a chance to do the work automatically at least.
                    val worstJob = specialistAllocations.keys.minByOrNull {
                        Automation.rankSpecialist(it, city, city.cityStats.currentCityStats) }
                        ?: break // sorry, we can do nothing about that
                    specialistAllocations.add(worstJob, -1)
                }
            }
        }

    }

    fun getMaxSpecialists(): Counter<String> {
        val counter = Counter<String>()
        for (building in city.cityConstructions.getBuiltBuildings())
            counter.add(building.newSpecialists())
        return counter
    }
}