package com.workert.robotics.base.roboscriptbytecode;

import java.util.ArrayList;
import java.util.List;

final class Chunk {
	private List<Byte> code = new ArrayList<>();
	private List<Object> constants = new ArrayList<>();
	private List<Integer> lines = new ArrayList<>();


	// Add code to the chunk
	void writeCode(byte code, int line) {
		this.code.add(code);
		this.lines.add(line);
	}

	void setCode(int index, byte code) {
		this.code.set(index, code);
	}

	byte readCode(int i) {
		return this.code.get(i);
	}

	int getCodeSize() {
		return this.code.size();
	}


	// Add a value to the chunk
	int addConstant(Object value) {
		this.constants.add(value);
		return this.constants.size() - 1;
	}


	Object readConstant(int i) {
		return this.constants.get(Math.abs(i));
	}


	int readLine(int i) {
		return this.lines.get(i);
	}


}
