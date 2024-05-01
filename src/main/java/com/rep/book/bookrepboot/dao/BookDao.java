package com.rep.book.bookrepboot.dao;

import java.util.List;
import java.util.Map;


import com.rep.book.bookrepboot.dto.BookDTO;

import lombok.NonNull;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookDao {
	
	List<BookDTO> getBookList(String keyword);

	String getImage(String bookIsbn);
	
	void saveBook(BookDTO bookDTO);

	BookDTO getBookByIsbn(String isbn);

	List<BookDTO> getBookmarkByEmail(String email);

    List<BookDTO> getBookForTradeById(String userEmail);

	List<BookDTO> getOwnBookByKeyword(Map<String, String> map);

	String getBookNameByIsbn(String bookIsbn);
}
