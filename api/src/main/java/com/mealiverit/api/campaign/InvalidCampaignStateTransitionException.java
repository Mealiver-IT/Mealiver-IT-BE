package com.mealiverit.api.campaign;

public class InvalidCampaignStateTransitionException extends RuntimeException {

    public InvalidCampaignStateTransitionException(CampaignStatus from, CampaignStatus to) {
        super("Cannot transition campaign status from " + from + " to " + to);
    }
}
