package org.javaup.exception;


import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.enums.BaseCode;

/**
 * @description: 业务异常
 **/
@EqualsAndHashCode(callSuper = true)
@Data
public class ShowGoFrameException extends BaseException {
	
	private Integer code;

	public ShowGoFrameException() {
		super();
	}

	public ShowGoFrameException(String message) {
		super(message);
	}
	
	public ShowGoFrameException(Integer code, String message) {
		super(message);
		this.code = code;
	}
	
	public ShowGoFrameException(BaseCode baseCode) {
		super(baseCode.getMsg());
		this.code = baseCode.getCode();
	}

	public ShowGoFrameException(Throwable cause) {
		super(cause);
	}

	public ShowGoFrameException(String message, Throwable cause) {
		super(message, cause);
	}
}
