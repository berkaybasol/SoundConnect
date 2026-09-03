package com.berkayb.soundconnect.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(RateLimitedException.class)
	public ResponseEntity<ErrorResponse> handleRateLimitedException(
			RateLimitedException exception,
			HttpServletRequest request
	) {
		ResponseEntity<ErrorResponse> contract = response(exception.getErrorType(), request);
		return ResponseEntity.status(contract.getStatusCode())
				.header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
				.body(contract.getBody());
	}

	@ExceptionHandler(ServiceUnavailableRetryException.class)
	public ResponseEntity<ErrorResponse> handleServiceUnavailableRetryException(
			ServiceUnavailableRetryException exception,
			HttpServletRequest request
	) {
		ResponseEntity<ErrorResponse> contract = response(exception.getErrorType(), request);
		return ResponseEntity.status(contract.getStatusCode())
				.header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
				.body(contract.getBody());
	}

	@ExceptionHandler(SoundConnectException.class)
	public ResponseEntity<ErrorResponse> handleSoundConnectException(
			SoundConnectException exception,
			HttpServletRequest request
	) {
		ErrorType errorType = exception.getErrorType();
		if (exception.getDetails() != null && !exception.getDetails().isEmpty()) {
			log.debug("Custom domain details suppressed. path={}, errorCode={}, detailCount={}",
					request.getRequestURI(), errorType.getCode(), exception.getDetails().size());
		}
		return response(errorType, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableMessage(
			HttpMessageNotReadableException exception,
			HttpServletRequest request
	) {
		log.debug("Unreadable HTTP message. path={}, exceptionType={}",
				request.getRequestURI(), exception.getClass().getSimpleName());
		return response(ErrorType.MALFORMED_REQUEST, request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(
			MethodArgumentTypeMismatchException exception,
			HttpServletRequest request
	) {
		String detail = "Parameter '" + exception.getName() + "' has an invalid value.";
		return response(ErrorType.TYPE_MISMATCH, request, List.of(detail));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
			MethodArgumentNotValidException exception,
			HttpServletRequest request
	) {
		return response(ErrorType.VALIDATION_ERROR, request, validationDetails(exception.getBindingResult()));
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<ErrorResponse> handleBindingException(
			BindException exception,
			HttpServletRequest request
	) {
		return response(ErrorType.VALIDATION_ERROR, request, validationDetails(exception.getBindingResult()));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(
			HandlerMethodValidationException exception,
			HttpServletRequest request
	) {
		List<String> details = exception.getAllErrors().stream()
				.map(GlobalExceptionHandler::resolveMessage)
				.distinct()
				.sorted()
				.toList();
		return response(ErrorType.CONSTRAINT_VIOLATION, request, details);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(
			ConstraintViolationException exception,
			HttpServletRequest request
	) {
		List<String> details = exception.getConstraintViolations().stream()
				.map(GlobalExceptionHandler::constraintDetail)
				.distinct()
				.sorted()
				.toList();
		return response(ErrorType.CONSTRAINT_VIOLATION, request, details);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(
			MissingServletRequestParameterException exception,
			HttpServletRequest request
	) {
		String detail = "Required parameter '" + exception.getParameterName() + "' is missing.";
		return response(ErrorType.MISSING_REQUEST_PARAMETER, request, List.of(detail));
	}

	@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
	public ResponseEntity<ErrorResponse> handleNoResourceFound(
			Exception exception,
			HttpServletRequest request
	) {
		return response(ErrorType.ENDPOINT_NOT_FOUND, request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(
			HttpRequestMethodNotSupportedException exception,
			HttpServletRequest request
	) {
		ResponseEntity<ErrorResponse> base = response(ErrorType.METHOD_NOT_ALLOWED, request);
		var supportedMethods = exception.getSupportedHttpMethods();
		if (supportedMethods == null || supportedMethods.isEmpty()) {
			return base;
		}
		return ResponseEntity.status(ErrorType.METHOD_NOT_ALLOWED.getHttpStatus())
				.allow(supportedMethods.toArray(HttpMethod[]::new))
				.body(base.getBody());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
			HttpMediaTypeNotSupportedException exception,
			HttpServletRequest request
	) {
		return response(ErrorType.UNSUPPORTED_MEDIA_TYPE, request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
			DataIntegrityViolationException exception,
			HttpServletRequest request
	) {
		// Database/vendor messages may contain SQL, schema names, or submitted values.
		log.warn("Data integrity conflict. path={}, exceptionType={}",
				request.getRequestURI(), exception.getClass().getSimpleName());
		return response(ErrorType.DATA_INTEGRITY_CONFLICT, request);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAuthenticationFailure(
			AuthenticationException exception,
			HttpServletRequest request
	) {
		log.debug("Authentication rejected. path={}, exceptionType={}",
				request.getRequestURI(), exception.getClass().getSimpleName());
		return response(ErrorType.UNAUTHORIZED, request);
	}

	@ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
	public ResponseEntity<ErrorResponse> handleAccessDenied(
			Exception exception,
			HttpServletRequest request
	) {
		log.warn("Access denied. path={}, exceptionType={}",
				request.getRequestURI(), exception.getClass().getSimpleName());
		return response(ErrorType.FORBIDDEN_ACCESS, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(
			Exception exception,
			HttpServletRequest request
	) {
		log.error("Unexpected server error. path={}, exceptionType={}",
				request.getRequestURI(), exception.getClass().getName(), exception);
		return response(ErrorType.INTERNAL_ERROR, request);
	}

	private static List<String> validationDetails(BindingResult bindingResult) {
		List<String> details = new ArrayList<>();
		bindingResult.getFieldErrors().forEach(error ->
				details.add(error.getField() + ": " + resolveMessage(error)));
		bindingResult.getGlobalErrors().forEach(error ->
				details.add(error.getObjectName() + ": " + resolveMessage(error)));
		return details.stream().distinct().sorted().toList();
	}

	private static String constraintDetail(ConstraintViolation<?> violation) {
		String path = violation.getPropertyPath() == null ? "request" : violation.getPropertyPath().toString();
		if (path.isBlank()) {
			path = "request";
		}
		return path + ": " + safeMessage(violation.getMessage());
	}

	private static String resolveMessage(MessageSourceResolvable error) {
		return safeMessage(error.getDefaultMessage());
	}

	private static String safeMessage(String message) {
		return message == null || message.isBlank() ? "Invalid value" : message;
	}

	private static ResponseEntity<ErrorResponse> response(
			ErrorType errorType,
			HttpServletRequest request
	) {
		return response(errorType, request, List.of(errorType.getDetails()));
	}

	private static ResponseEntity<ErrorResponse> response(
			ErrorType errorType,
			HttpServletRequest request,
			List<String> details
	) {
		List<String> safeDetails = details == null || details.isEmpty()
				? List.of(errorType.getDetails())
				: List.copyOf(details);

		ErrorResponse body = ErrorResponse.builder()
				.message(errorType.getMessage())
				.code(errorType.getCode())
				.httpStatus(errorType.getHttpStatus())
				.path(request.getRequestURI())
				.timestamp(LocalDateTime.now())
				.details(safeDetails)
				.build();

		return ResponseEntity.status(errorType.getHttpStatus()).body(body);
	}
}
