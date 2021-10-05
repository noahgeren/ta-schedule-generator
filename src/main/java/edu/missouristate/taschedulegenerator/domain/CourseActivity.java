package edu.missouristate.taschedulegenerator.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;


@NoArgsConstructor
@AllArgsConstructor
@Data
public class CourseActivity {
	
	private String activityName;
	
	private boolean mustBeTA;
	
	private int hoursNeeded;

	private TimeBlock time;
	
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	@JsonIgnore
	private Course course;
}
