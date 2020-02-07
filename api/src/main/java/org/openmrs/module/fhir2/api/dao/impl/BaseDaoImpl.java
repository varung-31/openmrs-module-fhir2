/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.dao.impl;

import static org.hibernate.criterion.Order.asc;
import static org.hibernate.criterion.Order.desc;
import static org.hibernate.criterion.Restrictions.and;
import static org.hibernate.criterion.Restrictions.eq;
import static org.hibernate.criterion.Restrictions.ge;
import static org.hibernate.criterion.Restrictions.gt;
import static org.hibernate.criterion.Restrictions.ilike;
import static org.hibernate.criterion.Restrictions.le;
import static org.hibernate.criterion.Restrictions.lt;
import static org.hibernate.criterion.Restrictions.not;
import static org.hibernate.criterion.Restrictions.or;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.hibernate.Criteria;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Order;
import org.hibernate.internal.CriteriaImpl;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.codesystems.AdministrativeGender;

/**
 * A base class for OpenMRS FHIR2 Dao objects. It provides helpers to make generating complex queries simpler.
 */
public abstract class BaseDaoImpl {

	boolean containsAlias(Criteria criteria, @NotNull String alias) {
		Optional<Iterator<CriteriaImpl.Subcriteria>> subcriteria =  asImpl(criteria).map(CriteriaImpl::iterateSubcriteria);

		return subcriteria.filter(subcriteriaIterator -> containsAlias(subcriteriaIterator, alias)).isPresent();

	}

	boolean containsAlias(Iterator<CriteriaImpl.Subcriteria> subcriteriaIterator, @NotNull String alias) {
		return stream(subcriteriaIterator).anyMatch(sc -> sc.getAlias().equals(alias));
	}

	/**
	 * Converts an {@link Iterable} to a {@link Stream}
	 *
	 * @param iterable the iterable
	 * @param <T>      any type
	 * @return a stream containing the same objects as the iterable
	 */
	static <T> Stream<T> stream(Iterable<T> iterable) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterable.iterator(), Spliterator.ORDERED), false);
	}

	/**
	 * Converts an {@link Iterator} to a {@link Stream}
	 *
	 * @param iterator the iterator
	 * @param <T>      any type
	 * @return a stream containing the same objects as the iterator
	 */
	static <T> Stream<T> stream(Iterator<T> iterator) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
	}

	/**
	 * Generic handler for a {@link TokenAndListParam}
	 *
	 * @param andListParam  the {@link TokenAndListParam} to process
	 * @param orListHandler the handler to turn a {@link TokenAndListParam} into an {@link Criterion}
	 * @return a {@link Criterion} that contains the intersection of all {@link TokenOrListParam}
	 */
	Optional<Criterion> handleAndListParam(TokenAndListParam andListParam, TokenOrListParamHandler orListHandler) {
		if (andListParam == null) {
			return Optional.empty();
		}

		return Optional.of(and(andListParam.getValuesAsQueryTokens().stream().map(orListHandler).filter(Optional::isPresent)
				.map(Optional::get).toArray(Criterion[]::new)));
	}

	/**
	 * Generic handler for a {@link TokenAndListParam} with default behavior for all the {@link TokenOrListParam}s
	 *
	 * @param andListParam the {@link TokenAndListParam} to process
	 * @param tokenHandler a {@link TokenParamHandler} that turns a {@link TokenParam} into a {@link Criterion}
	 * @return a {@link Criterion} that contains the intersection of all {@link TokenOrListParam}
	 */
	Optional<Criterion> handleAndListParam(TokenAndListParam andListParam, TokenParamHandler tokenHandler) {
		return handleAndListParam(andListParam,
				(TokenOrListParamHandler) tlp -> handleOrListParam(tlp, tokenHandler));
	}

	/**
	 * Generic handler for a {@link TokenOrListParam}
	 *
	 * @param orListParam  the {@link TokenOrListParam} to handle
	 * @param tokenHandler a {@link TokenParamHandler} the turns a {@link TokenParam} into a {@link Criterion}
	 * @return the resulting {@link Criterion}, consisting of the union of all contained {@link TokenParam}s
	 */
	Optional<Criterion> handleOrListParam(TokenOrListParam orListParam, TokenParamHandler tokenHandler) {
		if (orListParam == null) {
			return Optional.empty();
		}

		return Optional.of(or(orListParam.getValuesAsQueryTokens().stream().map(tokenHandler).filter(Optional::isPresent)
				.map(Optional::get).toArray(Criterion[]::new)));
	}

	/**
	 * Handler for a {@link TokenOrListParam} where tokens should be grouped and handled according to the system
	 * they belong to
	 *
	 * This is useful for queries drawing their values from CodeableConcepts
	 *
	 * @param orListParam        the {@link TokenOrListParam} to handle
	 * @param systemTokenHandler a {@link BiFunction} taking the system and associated list of
	 *  {@link TokenParam}s and returning a {@link Criterion}
	 * @return a {@link Criterion} representing the union of all produced {@link Criterion}
	 */
	Optional<Criterion> handleOrListParamBySystem(TokenOrListParam orListParam,
			BiFunction<String, List<TokenParam>, Optional<Criterion>> systemTokenHandler) {

		if (orListParam == null) {
			return Optional.empty();
		}

		return Optional
				.of(or(orListParam.getValuesAsQueryTokens().stream().collect(Collectors.groupingBy(this::groupBySystem))
						.entrySet()
						.stream().map(e -> systemTokenHandler.apply(e.getKey(), e.getValue()))
						.filter(Optional::isPresent)
						.map(Optional::get).toArray(Criterion[]::new)));
	}

	Optional<Criterion> handleBoolean(String propertyName, TokenOrListParam booleanToken) {
		if (booleanToken == null) {
			return Optional.empty();
		}

		return handleOrListParam(booleanToken, token -> {
			if (token.getValue().equalsIgnoreCase("true")) {
				return Optional.of(eq(propertyName, true));
			} else if (token.getValue().equalsIgnoreCase("false")) {
				return Optional.of(eq(propertyName, false));
			}

			return Optional.empty();
		});
	}

	Optional<Criterion> handleDateRange(String propertyName, DateRangeParam dateRangeParam) {
		if (dateRangeParam == null) {
			return Optional.empty();
		}

		return Optional.of(and(Stream.of(handleDate(propertyName, dateRangeParam.getLowerBound()),
				handleDate(propertyName, dateRangeParam.getUpperBound())).filter(Optional::isPresent)
				.map(Optional::get).toArray(Criterion[]::new)));
	}

	Optional<Criterion> handleDate(String propertyName, DateParam dateParam) {
		if (dateParam == null) {
			return Optional.empty();
		}

		Date dayStart, dayEnd;
		if (dateParam.getPrecision().ordinal() > TemporalPrecisionEnum.DAY.ordinal()) {
			dayStart = DateUtils.truncate(dateParam.getValue(), Calendar.DATE);
		} else {
			dayStart = dateParam.getValue();
		}

		dayEnd = DateUtils.ceiling(dayStart, Calendar.DATE);

		switch (dateParam.getPrefix()) {
			case EQUAL:
				return Optional.of(and(ge(propertyName, dayStart), lt(propertyName, dayEnd)));
			case NOT_EQUAL:
				return Optional.of(not(and(ge(propertyName, dayStart), lt(propertyName, dayEnd))));
			case LESSTHAN_OR_EQUALS:
			case LESSTHAN:
				return Optional.of(le(propertyName, dayEnd));
			case GREATERTHAN_OR_EQUALS:
			case GREATERTHAN:
				return Optional.of(ge(propertyName, dayStart));
			case STARTS_AFTER:
				return Optional.of(gt(propertyName, dayEnd));
			case ENDS_BEFORE:
				return Optional.of(lt(propertyName, dayStart));
		}

		return Optional.empty();
	}

	Optional<Criterion> handleEncounterReference(@NotNull String encounterAlias, ReferenceParam encounterReference) {
		if (encounterReference == null || encounterReference.getIdPart() == null) {
			return Optional.empty();

		}

		return Optional.of(eq(String.format("%s.uuid", encounterAlias), encounterReference.getIdPart()));
	}

	Optional<Criterion> handleGender(@NotNull String propertyName, TokenOrListParam gender) {
		if (gender == null) {
			return Optional.empty();
		}

		return handleOrListParam(gender, token -> {
			try {
				AdministrativeGender administrativeGender = AdministrativeGender.fromCode(token.getValue());
				switch (administrativeGender) {
					case MALE:
						return Optional.of(ilike(propertyName, "M", MatchMode.EXACT));
					case FEMALE:
						return Optional.of(ilike(propertyName, "F", MatchMode.EXACT));
				}
			}
			catch (FHIRException ignored) {
			}

			return Optional.empty();
		});
	}

	void handlePatientReference(Criteria criteria, ReferenceParam patientReference) {
		if (patientReference != null) {
			criteria.createAlias("person", "p");

			if (patientReference.getChain() != null) {
				switch (patientReference.getChain()) {
					case Patient.SP_IDENTIFIER:
						criteria.createAlias("p.identifiers", "pi").add(ilike("pi.identifier", patientReference.getValue()));
						break;
					case Patient.SP_GIVEN:
						criteria.createAlias("p.names", "pn")
								.add(ilike("pn.givenName", patientReference.getValue(), MatchMode.START));
						break;
					case Patient.SP_FAMILY:
						criteria.createAlias("p.names", "pn")
								.add(ilike("pn.familyName", patientReference.getValue(), MatchMode.START));
						break;
					case Patient.SP_NAME:
						criteria.createAlias("p.names", "pn");
						List<Criterion> criterionList = new ArrayList<>();

						for (String token : StringUtils.split(patientReference.getValue(), " \t,")) {
							propertyLike("pn.givenName", token).ifPresent(criterionList::add);
							propertyLike("pn.middleName", token).ifPresent(criterionList::add);
							propertyLike("pn.familyName", token).ifPresent(criterionList::add);
						}

						criteria.add(or(criterionList.toArray(new Criterion[0])));
						break;
					case "":
						criteria.add(eq("p.uuid", patientReference.getValue()));
						break;
				}
			}
		}
	}

	void handleSort(Criteria criteria, SortSpec sort, Function<String, String> paramToProp) {
		handleSort(sort, paramToProp).ifPresent(l -> l.forEach(criteria::addOrder));
	}

	Optional<List<Order>> handleSort(SortSpec sort, Function<String, String> paramToProp) {
		List<Order> orderings = new ArrayList<>();
		SortSpec sortSpec = sort;
		while (sortSpec != null) {
			String prop = paramToProp.apply(sortSpec.getParamName());
			if (prop != null) {
				switch (sortSpec.getOrder()) {
					case DESC:
						orderings.add(desc(prop));
						break;
					case ASC:
						orderings.add(asc(prop));
						break;
				}
			}

			sortSpec = sortSpec.getChain();
		}

		if (orderings.size() == 0) {
			return Optional.empty();
		} else {
			return Optional.of(orderings);
		}
	}

	Optional<Criterion> propertyLike(@NotNull String propertyName, StringParam param) {
		if (param == null) {
			return Optional.empty();
		}

		if (param.isExact()) {
			return Optional.of(ilike(propertyName, param.getValue(), MatchMode.EXACT));
		} else if (param.isContains()) {
			return Optional.of(ilike(propertyName, param.getValue(), MatchMode.ANYWHERE));
		} else {
			return Optional.of(ilike(propertyName, param.getValue(), MatchMode.START));
		}
	}

	Optional<Criterion> propertyLike(@NotNull String propertyName, String value) {
		if (value == null) {
			return Optional.empty();
		}

		return propertyLike(propertyName, new StringParam(value));
	}

	String groupBySystem(@NotNull TokenParam token) {
		return StringUtils.trimToEmpty(token.getSystem());
	}

	Optional<CriteriaImpl> asImpl(Criteria criteria) {
		if (CriteriaImpl.class.isAssignableFrom(criteria.getClass())) {
			return Optional.of((CriteriaImpl) criteria);
		} else {
			return Optional.empty();
		}
	}
}
