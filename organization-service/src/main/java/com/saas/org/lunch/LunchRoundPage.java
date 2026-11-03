package com.saas.org.lunch;

import java.util.List;

public record LunchRoundPage(List<LunchRoundView> items, int page, int size, long totalItems) {
}
