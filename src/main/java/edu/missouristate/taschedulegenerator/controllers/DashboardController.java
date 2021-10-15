package edu.missouristate.taschedulegenerator.controllers;

import edu.missouristate.taschedulegenerator.util.SceneManager;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;

public class DashboardController {
	
	@FXML
	public void addCourseInfo(ActionEvent event) {
		// This is an example of how to switch scenes and pass data to the new scene's controller to process before showing
		SceneManager.showScene("courseInfo", "CSC450");
	}

}