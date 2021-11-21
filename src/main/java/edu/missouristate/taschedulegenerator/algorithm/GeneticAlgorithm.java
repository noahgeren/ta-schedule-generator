package edu.missouristate.taschedulegenerator.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import edu.missouristate.taschedulegenerator.domain.Activity;
import edu.missouristate.taschedulegenerator.domain.Schedule;
import edu.missouristate.taschedulegenerator.domain.Schedule.ScheduledActivity;
import edu.missouristate.taschedulegenerator.domain.TA;

/*
 * TODO: Add repair method that fixes simple issues like GA sections having over/under or GAs over/under
 * TODO: Improve error calculating
 */
public class GeneticAlgorithm implements Runnable, Comparator<int[]> {
	
	private static final int POPULATION_SIZE = 250;
	private static final int TOURNAMENT_SIZE = 10;
	private static final int ELITE_COUNT = 10;
	private static final double CROSSOVER_RATE = 0.5;
	private static final double MUTATION_RATE = 0.2;
	// Error multipliers
	private static final int SECTION_OVERLAP = 1000;
	private static final int MISSED_SECTION = 1000;
	private static final int TA_OVER_HOURS = 500; // TA got assigned too many hours
	private static final int TA_UNDER_HOURS = 150; // TA got assigned too few hours
	private static final int ACTIVITY_UNDER_HOURS = 100; // Activity got assigned too few hours
	private static final int ACTIVITY_OVER_HOURS = 50; // Activity got assigned too many hours
	private static final int NOT_ALL_SAME_TA = 25; // Course activities were assigned to different TAs
	private static final int RANKINGS_DISTANCE = 5; // The difference between TA course preferences and actual assigned courses
	
	private final List<TA> tas;
	private final List<Activity> activities;
	private final Map<Integer, List<Integer>> tasByActivity = new HashMap<>();
	private final int geneLength;
	private final CompletableFuture<List<Schedule>> completableFuture;
	
	private final Random random = new Random();
	
	public GeneticAlgorithm(final List<TA> tas, final List<Activity> activities, final CompletableFuture<List<Schedule>> completableFuture) {
		super();
		this.tas = tas;
		this.activities = activities;
		this.geneLength = activities.size() * 2 + 1;
		this.completableFuture = completableFuture;
		
		for(int activityIdx = 0; activityIdx < activities.size(); activityIdx++) {
			final Activity activity = activities.get(activityIdx);
			final List<Integer> tasForActivity = new ArrayList<>();
			for(int taIdx = 0; taIdx < tas.size(); taIdx++) {
				final TA ta = tas.get(taIdx);
				// If activity needs TA and if TA unavailable times don't intersect with activity time
				if(!(activity.isMustBeTA() && ta.isGA()) &&
						(activity.getTime() == null || ta.getNotAvailable().stream().noneMatch(notAvailable -> notAvailable.intersects(activity.getTime())))) {
					tasForActivity.add(taIdx);
				}
			}
			// TODO: If no TAs can take activity then throw exception
			tasByActivity.put(activityIdx, tasForActivity);
		}
	}

	@Override
	public void run() {
		int[][] population = new int[POPULATION_SIZE][];
		for(int i = 0; i < POPULATION_SIZE; i++) {
			population[i] = getRandomSchedule();
		}
		Arrays.sort(population, this);
		while(!Thread.currentThread().isInterrupted()) {
			crossover(population);
			mutate(population);
			
			for(int i = 0; i < POPULATION_SIZE; i++) {
				population[i][geneLength - 1] = -1;
			}
			
			Arrays.sort(population, this);
		}
		final LinkedHashSet<Schedule> bestSchedules = new LinkedHashSet<>();
		for(int i = 0; bestSchedules.size() < ELITE_COUNT && i < POPULATION_SIZE; i++) {
			final List<ScheduledActivity> scheduledActivities = new ArrayList<>(activities.size());
			for(int j = 0; j < geneLength - 1; j += 2) {
				scheduledActivities.add(new ScheduledActivity(activities.get(j / 2), tas.get(population[i][j]), population[i][j + 1]));
			}
			final Schedule schedule = new Schedule(scheduledActivities, population[i][geneLength - 1]);
			calculateScheduleError(population[i], schedule.getErrorLog());
			bestSchedules.add(schedule);
		}
		completableFuture.complete(new ArrayList<>(bestSchedules));
	}
	
	private void crossover(final int[][] population) {
		for(int i = 0; i < population.length - ELITE_COUNT; i++) {
			if(i > ELITE_COUNT && random.nextDouble() < CROSSOVER_RATE) { // Do crossover
				generateOffspring(population[i], selectParent(population), 0.5); // Use half of each parent's genes
			} else if(i <= ELITE_COUNT) {
				population[population.length - i - 1] = Arrays.copyOf(population[i], population[i].length);
				generateOffspring(population[population.length - i - 1], selectParent(population), 0.5);
			}
		}
	}
	
	private int[] selectParent(final int[][] population) {
		int min = Integer.MAX_VALUE;
		for(int i = 0; i < TOURNAMENT_SIZE; i++) {
			min = Math.min(min, random.nextInt(population.length));
		}
		return population[min];
	}
	
	private void generateOffspring(final int[] parentOne, final int[] parentTwo, final double exchangeRate) {
		for(int i = 0; i < geneLength - 1; i++) {
			if(random.nextDouble() < exchangeRate) {
				parentOne[i] = parentTwo[i];
			}
		}
	}
	
	private void mutate(final int[][] population) {
		for(int i = 0; i < population.length - ELITE_COUNT; i++) {
			if(i > ELITE_COUNT) {
				generateOffspring(population[i], getRandomSchedule(), MUTATION_RATE);
			}else {
				population[population.length - i - 1] = Arrays.copyOf(population[i], population[i].length);
				generateOffspring(population[population.length - i - 1], getRandomSchedule(), MUTATION_RATE);
			}
		}
	}
	
	private int[] getRandomSchedule() {
		final int[] schedule = new int[geneLength];
		int i = 0;
		for(final Entry<Integer, List<Integer>> entry : tasByActivity.entrySet()) {
			schedule[i * 2] = entry.getValue().get(random.nextInt(entry.getValue().size()));
			// TODO: Make better hour generator
			schedule[i++ * 2 + 1] = (int)Math.max((random.nextGaussian() * 4) + activities.get(entry.getKey()).getHoursNeeded(), 1);
		}
		return schedule;
	}
	
	// TODO: Instead of weighting by hours off, weight by ratio of hours off
	private int calculateScheduleError(final int[] schedule, final List<String> errorLog) {
		final boolean log = errorLog != null;
		int error = 0;
		for(int i = 0; i < geneLength - 3; i += 2) {
			for(int j = i + 2; j < geneLength - 2; j += 2) {
				if(schedule[i] == schedule[j]) {
					final Activity activityOne = activities.get(i / 2);
					final Activity activityTwo = activities.get(j / 2);
					if(activityOne.getTime() != null && activityTwo.getTime() != null
							&& activityOne.getTime().intersects(activityTwo.getTime())) {
						error += SECTION_OVERLAP;
						if(log) {
							errorLog.add(String.format("Activity %s - %s and %s - %s overlap", activityOne.getCourse().getCourseCode(), activityOne.getName(),
									activityTwo.getCourse().getCourseCode(), activityTwo.getName()));
						}
					}
				}
			}
		}
		final HashMap<Integer, Integer> taHours = new HashMap<>();
		for(int i = 0; i < geneLength - 2; i += 2) {
			final Activity activity = activities.get(i / 2);
			final String activityName = String.format("%s - %s", activity.getCourse().getCourseCode(), activity.getName());
			Integer hours = taHours.getOrDefault(schedule[i], 0);	
			if(schedule[i + 1] > 0) {
				taHours.put(schedule[i], hours + schedule[i + 1]);
			} 
			// TODO: Redo this and add missed TA
			//else {
			//	error += MISSED_SECTION;
			//	if(log) {
			//		errorLog.add(String.format("Missed Activity %s", activityName));
			//	}
			//}
			
			if(activity.getHoursNeeded() > schedule[i + 1]) {
				error += ACTIVITY_UNDER_HOURS * (activity.getHoursNeeded() - schedule[i + 1]);
				if(log) {
					errorLog.add(String.format("Activity %s under hours", activityName));
				}
			} else if(activity.getHoursNeeded() < schedule[i + 1]) {
				error += ACTIVITY_OVER_HOURS * (schedule[i + 1] - activity.getHoursNeeded());
				if(log) {
					errorLog.add(String.format("Activity %s over hours", activityName));
				}
			}
		}
		for(int i = 0; i < tas.size(); i++) {
			final TA ta = tas.get(i);
			final int hours = taHours.getOrDefault(i, 0);
			if(hours > ta.getMaxHours()) {
				error += TA_OVER_HOURS * (hours - ta.getMaxHours());
				if(log) {
					errorLog.add(String.format("TA %s over hours", ta.getName()));
				}
			} else if(hours < ta.getMaxHours()) {
				error += TA_UNDER_HOURS * (ta.getMaxHours() - hours);
				if(log) {
					errorLog.add(String.format("TA %s under hours", ta.getName()));
				}
			}
		}
		return error >= 0 ? error : Integer.MAX_VALUE; // Just in case of integer overflow
	}

	@Override
	public int compare(int[] s1, int[] s2) {
		if(s1[geneLength - 1] == -1) {
			s1[geneLength - 1] = calculateScheduleError(s1, null);
		}
		if(s2[geneLength - 1] == -1) {
			s2[geneLength - 1] = calculateScheduleError(s2, null);
		}
		return s1[geneLength - 1] - s2[geneLength - 1];
	}

}